package com.turbolego.songguesser

import android.content.Context
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max

enum class GameMode {
    CLASSIC,
    ARCADE,
}

enum class RandomizationMode {
    PURE_RANDOM,
    PRIORITIZE_MODERN,
    CUSTOM,
}

/**
 * Persistent, Android-native counterparts to the web game's player, mode, and
 * probability settings. Keeping this state outside fragments prevents a device
 * rotation or a new game from silently reverting a party's configuration.
 */
object GamePreferences {
    const val MIN_PLAYERS = 2
    const val MAX_PLAYERS = 8
    const val MIN_YEAR = 1960
    const val MAX_YEAR = 2025

    private const val PREFS = "game_preferences"
    private const val KEY_PLAYER_NAMES = "player_names"
    private const val KEY_GAME_MODE = "game_mode"
    private const val KEY_RANDOMIZATION = "randomization"
    private const val KEY_DECADE_WEIGHTS = "decade_weights"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun playerNames(context: Context): List<String> {
        val stored = prefs(context).getString(KEY_PLAYER_NAMES, "")
            ?.split("|")
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            .orEmpty()
        return if (stored.size >= MIN_PLAYERS) stored.take(MAX_PLAYERS) else listOf("A", "B")
    }

    fun savePlayerNames(context: Context, names: List<String>) {
        val cleaned = names
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(MAX_PLAYERS)
        if (cleaned.size >= MIN_PLAYERS) {
            prefs(context).edit().putString(KEY_PLAYER_NAMES, cleaned.joinToString("|")).apply()
        }
    }

    fun gameMode(context: Context): GameMode = when (prefs(context).getString(KEY_GAME_MODE, null)) {
        GameMode.ARCADE.name -> GameMode.ARCADE
        else -> GameMode.CLASSIC
    }

    fun setGameMode(context: Context, mode: GameMode) {
        prefs(context).edit().putString(KEY_GAME_MODE, mode.name).apply()
    }

    fun randomizationMode(context: Context): RandomizationMode =
        runCatching {
            RandomizationMode.valueOf(
                prefs(context).getString(KEY_RANDOMIZATION, RandomizationMode.PRIORITIZE_MODERN.name)
                    ?: RandomizationMode.PRIORITIZE_MODERN.name
            )
        }.getOrDefault(RandomizationMode.PRIORITIZE_MODERN)

    fun setRandomizationMode(context: Context, mode: RandomizationMode) {
        prefs(context).edit().putString(KEY_RANDOMIZATION, mode.name).apply()
    }

    fun decadeStarts(): List<Int> = (MIN_YEAR..MAX_YEAR step 10).toList()

    fun defaultWeights(mode: RandomizationMode): Map<Int, Int> {
        val decades = decadeStarts()
        val rawWeights = when (mode) {
            RandomizationMode.PRIORITIZE_MODERN -> decades.mapIndexed { index, decade ->
                decade to (index + 1)
            }.toMap()
            else -> decades.associateWith { 1 }
        }
        return normalizeWeights(rawWeights)
    }

    private fun normalizeWeights(weights: Map<Int, Int>): Map<Int, Int> {
        val decades = decadeStarts()
        val total = decades.sumOf { max(0, weights[it] ?: 0) }
        if (total == 0) return decades.associateWith { 0 }

        val normalized = decades.associateWith { decade ->
            ((max(0, weights[decade] ?: 0).toDouble() / total) * 100).toInt()
        }.toMutableMap()
        var remainder = 100 - normalized.values.sum()
        for (decade in decades.reversed()) {
            if (remainder == 0) break
            normalized[decade] = max(0, (normalized[decade] ?: 0) + if (remainder > 0) 1 else -1)
            remainder += if (remainder > 0) -1 else 1
        }
        return decades.associateWith { normalized[it] ?: 0 }
    }

    fun decadeWeights(context: Context): Map<Int, Int> {
        val raw = prefs(context).getString(KEY_DECADE_WEIGHTS, "").orEmpty()
        if (raw.isBlank()) return defaultWeights(RandomizationMode.PRIORITIZE_MODERN)
        val parsed = raw.split(",").mapNotNull { token ->
            val (decade, weight) = token.split(":", limit = 2).map(String::trim).let {
                if (it.size == 2) it else return@mapNotNull null
            }
            val start = decade.toIntOrNull() ?: return@mapNotNull null
            val value = weight.toIntOrNull() ?: return@mapNotNull null
            if (start in MIN_YEAR..MAX_YEAR && value >= 0) start to value else null
        }.toMap()
        return if (parsed.isEmpty()) defaultWeights(RandomizationMode.PRIORITIZE_MODERN)
        else decadeStarts().associateWith { parsed[it] ?: 0 }
    }

    fun saveDecadeWeights(context: Context, weights: Map<Int, Int>) {
        val normalized = decadeStarts().joinToString(",") { decade ->
            "$decade:${max(0, weights[decade] ?: 0)}"
        }
        prefs(context).edit().putString(KEY_DECADE_WEIGHTS, normalized).apply()
    }

    /**
     * Mirrors the web app's slider behavior: the edited decade keeps its chosen
     * share and the remaining active decades are rescaled to keep a 100-point
     * distribution. It is deliberately pure for straightforward unit testing.
     */
    fun redistributeWeights(
        weights: Map<Int, Int>,
        selectedDecade: Int,
        selectedWeight: Int,
    ): Map<Int, Int> {
        val decades = decadeStarts()
        val target = selectedWeight.coerceIn(0, 100)
        val otherDecades = decades.filter { it != selectedDecade }
        val oldOtherTotal = otherDecades.sumOf { max(0, weights[it] ?: 0) }
        val targetOtherTotal = 100 - target
        val values = mutableMapOf<Int, Int>()
        values[selectedDecade] = target

        if (oldOtherTotal == 0) {
            val each = if (otherDecades.isEmpty()) 0 else targetOtherTotal / otherDecades.size
            otherDecades.forEach { values[it] = each }
        } else {
            otherDecades.forEach { decade ->
                values[decade] = ((max(0, weights[decade] ?: 0).toDouble() / oldOtherTotal) * targetOtherTotal)
                    .toInt()
            }
        }

        // Resolve rounding deterministically so the saved distribution always totals 100.
        var remainder = 100 - values.values.sum()
        for (decade in otherDecades.reversed()) {
            if (remainder == 0) break
            values[decade] = max(0, (values[decade] ?: 0) + if (remainder > 0) 1 else -1)
            remainder += if (remainder > 0) -1 else 1
        }
        return decades.associateWith { values[it] ?: 0 }
    }
}

/** Persistent history replaces the previous session-only duplicate avoidance. */
object PlayHistory {
    private const val PREFS = "play_history"
    private const val KEY_IDS = "played_video_ids"
    private const val KEY_ORDER = "played_video_ids_order"
    private const val KEY_DUPLICATES = "duplicate_candidates"
    internal const val MAX_ENTRIES = 1_000

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun contains(context: Context, videoId: String): Boolean =
        prefs(context).getStringSet(KEY_IDS, emptySet()).orEmpty().contains(videoId)

    fun record(context: Context, videoId: String) {
        val pref = prefs(context)
        val ordered = appendToHistory(readOrderedIds(pref), videoId)
        pref.edit()
            .putStringSet(KEY_IDS, ordered.toSet())
            .putString(KEY_ORDER, ordered.joinToString("\n"))
            .apply()
    }

    /**
     * Keeps the newest occurrence of an ID and trims the oldest IDs first. The
     * operation is pure so history rollover can be verified without Android I/O.
     */
    internal fun appendToHistory(current: List<String>, videoId: String): List<String> =
        (current.filter { it != videoId } + videoId).takeLast(MAX_ENTRIES)

    private fun readOrderedIds(pref: android.content.SharedPreferences): List<String> {
        val savedOrder = pref.getString(KEY_ORDER, "").orEmpty()
            .lineSequence()
            .filter(String::isNotBlank)
            .toList()
        if (savedOrder.isNotEmpty()) return savedOrder
        // Migrate legacy unordered entries without dropping an ID; chronological
        // order only becomes available after the next recorded round.
        return pref.getStringSet(KEY_IDS, emptySet()).orEmpty().toList()
    }

    fun recordDuplicateCandidate(context: Context) {
        val count = prefs(context).getInt(KEY_DUPLICATES, 0)
        prefs(context).edit().putInt(KEY_DUPLICATES, count + 1).apply()
    }

    fun duplicateCount(context: Context): Int = prefs(context).getInt(KEY_DUPLICATES, 0)
    fun historyCount(context: Context): Int = prefs(context).getStringSet(KEY_IDS, emptySet()).orEmpty().size

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_IDS).remove(KEY_ORDER).remove(KEY_DUPLICATES).apply()
    }
}

/** Lightweight per-player rollups, portable to the Android video catalog. */
object PlayerStatistics {
    data class Summary(
        val playerName: String,
        val guesses: Int,
        val exactGuesses: Int,
        val points: Int,
    )

    private const val PREFS = "player_statistics"
    private const val KEY_PLAYERS = "players"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Combines a legible SharedPreferences-safe slug with a hash of the original
     * normalized name so punctuation variants cannot merge player statistics.
     */
    internal fun storageKeyFor(name: String): String {
        val normalized = name.trim().lowercase(Locale.ROOT)
        val slug = normalized.replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "player" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(12)
        return "${slug}_$digest"
    }

    fun recordGuess(context: Context, playerName: String, guess: Int, answer: Int, points: Int) {
        val playerKey = storageKeyFor(playerName)
        val pref = prefs(context)
        val knownPlayers = pref.getStringSet(KEY_PLAYERS, emptySet()).orEmpty().toMutableSet()
        knownPlayers.add(playerName)
        pref.edit()
            .putStringSet(KEY_PLAYERS, knownPlayers)
            .putInt("${playerKey}_guesses", pref.getInt("${playerKey}_guesses", 0) + 1)
            .putInt("${playerKey}_exact", pref.getInt("${playerKey}_exact", 0) + if (guess == answer) 1 else 0)
            .putInt("${playerKey}_points", pref.getInt("${playerKey}_points", 0) + points)
            .apply()
    }

    fun summaries(context: Context): List<Summary> {
        val pref = prefs(context)
        return pref.getStringSet(KEY_PLAYERS, emptySet()).orEmpty().map { name ->
            val playerKey = storageKeyFor(name)
            Summary(
                playerName = name,
                guesses = pref.getInt("${playerKey}_guesses", 0),
                exactGuesses = pref.getInt("${playerKey}_exact", 0),
                points = pref.getInt("${playerKey}_points", 0),
            )
        }.sortedWith(compareByDescending<Summary> { it.points }.thenBy { it.playerName.lowercase(Locale.ROOT) })
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
