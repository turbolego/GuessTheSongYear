package com.turbolego.songguesser

import android.content.Context
import android.content.SharedPreferences
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.regex.Pattern
import kotlin.random.Random

/**
 * Provides random YouTube video IDs from either the default asset file
 * or a user-provided custom list.
 *
 * Zero network calls, zero API keys — entirely offline.
 */
object VideoProvider {

    data class VideoEntry(
        val id: String,
        val year: Int,
        val title: String = ""
    )

    // ── Preferences ────────────────────────────────────────────────────────

    private const val PREFS_NAME = "video_source_prefs"
    private const val KEY_SOURCE = "video_source"          // "default" or "custom"
    private const val KEY_CUSTOM_LIST = "custom_video_list" // raw user input

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Which video source the user has chosen. */
    enum class Source { DEFAULT, CUSTOM }

    fun getSource(context: Context): Source {
        val s = prefs(context).getString(KEY_SOURCE, "default") ?: "default"
        return if (s == "custom") Source.CUSTOM else Source.DEFAULT
    }

    fun setSource(context: Context, source: Source) {
        prefs(context).edit().putString(KEY_SOURCE, source.name.lowercase()).apply()
    }

    fun getCustomListRaw(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_LIST, "") ?: ""

    fun setCustomListRaw(context: Context, raw: String) {
        prefs(context).edit().putString(KEY_CUSTOM_LIST, raw).apply()
    }

    // ── In-memory cache ────────────────────────────────────────────────────

    private var cachedVideos: List<VideoEntry> = emptyList()
    private var yearIndex: Map<Int, List<VideoEntry>> = emptyMap()
    private var allYears: List<Int> = emptyList()
    private var loadedFromCustom = false

    /**
     * Load videos from the asset file. Call once on app startup.
     * Automatically switches to custom list if user has set one.
     */
    fun load(context: Context) {
        val source = getSource(context)
        if (source == Source.CUSTOM) {
            val raw = getCustomListRaw(context)
            if (raw.isNotBlank()) {
                loadFromCustomText(raw)
                return
            }
        }
        loadFromAssets(context)
    }

    /** Force-reload from the default asset file. */
    fun loadFromAssets(context: Context) {
        val videos = mutableListOf<VideoEntry>()
        try {
            context.assets.open("music_videos.txt").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                        val parts = trimmed.split(",", limit = 3)
                        if (parts.size >= 2) {
                            val id = parts[0].trim()
                            val year = parts[1].trim().toIntOrNull()
                            val title = if (parts.size >= 3) parts[2].trim() else ""
                            if (id.isNotEmpty() && year != null) {
                                videos.add(VideoEntry(id, year, title))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoProvider", "Failed to load asset videos", e)
        }
        setCache(videos)
        loadedFromCustom = false
    }

    /**
     * Load videos from user-provided text (URLs, bare IDs, mixed).
     * Extracts video IDs using regex.
     */
    fun loadFromCustomText(raw: String) {
        val videos = mutableListOf<VideoEntry>()
        val ids = parseVideoIds(raw)
        for (id in ids.distinct()) {
            videos.add(VideoEntry(id, 0, ""))
        }
        setCache(videos)
        loadedFromCustom = true
    }

    /**
     * Reload from whichever source the user currently has selected.
     * Call after changing the source preference.
     */
    fun reload(context: Context) {
        cachedVideos = emptyList()
        yearIndex = emptyMap()
        allYears = emptyList()
        load(context)
    }

    // ── Random selection ────────────────────────────────────────────────────

    fun getRandomVideoId(year: Int? = null): String? {
        val pool = if (year != null) {
            yearIndex[year]
        } else {
            cachedVideos.ifEmpty { null }
        }
        return pool?.randomOrNull()?.id
    }

    fun getRandomVideoIdWeighted(): String? {
        val year = if (loadedFromCustom) {
            // Custom list — no years known, pick from all
            null
        } else {
            YearRandomizer.prioritizeModernYears(availableYears = allYears)
        }
        return getRandomVideoId(year)
    }

    fun getRandomVideoEntryWeighted(): VideoEntry? {
        val year = if (loadedFromCustom) {
            null
        } else {
            YearRandomizer.prioritizeModernYears(availableYears = allYears)
        }
        val pool = if (year != null) yearIndex[year] else cachedVideos
        return pool?.randomOrNull()
    }

    /**
     * Game-aware entry selection. Custom URL lists have no reliable release-year
     * metadata, so they remain uniformly randomized regardless of the selected
     * probability mode.
     */
    fun getRandomVideoEntry(context: Context): VideoEntry? {
        if (cachedVideos.isEmpty()) return null
        if (loadedFromCustom) return cachedVideos.randomOrNull()

        val year = when (GamePreferences.randomizationMode(context)) {
            RandomizationMode.PURE_RANDOM -> allYears.randomOrNull()
            RandomizationMode.PRIORITIZE_MODERN ->
                YearRandomizer.prioritizeModernYears(availableYears = allYears)
            RandomizationMode.CUSTOM -> pickCustomWeightedYear(GamePreferences.decadeWeights(context))
        }
        return yearIndex[year]?.randomOrNull() ?: cachedVideos.randomOrNull()
    }

    private fun pickCustomWeightedYear(weights: Map<Int, Int>): Int? {
        val yearsByDecade = allYears.groupBy { year -> (year / 10) * 10 }
        val availableDecades = yearsByDecade.keys.sorted().filter { decade -> (weights[decade] ?: 0) > 0 }
        val totalWeight = availableDecades.sumOf { decade -> weights[decade] ?: 0 }
        if (totalWeight == 0) return allYears.randomOrNull()

        val selectedDecade = selectCustomWeightedDecade(
            weights = weights,
            availableDecades = availableDecades,
            randomRoll = Random.nextInt(totalWeight),
        ) ?: return allYears.randomOrNull()
        return yearsByDecade[selectedDecade]?.randomOrNull()
    }

    /**
     * Selects a decade from its configured share before choosing one of its
     * available years. This prevents decades with more catalogued years from
     * receiving more probability than their slider weight specifies.
     */
    internal fun selectCustomWeightedDecade(
        weights: Map<Int, Int>,
        availableDecades: List<Int>,
        randomRoll: Int,
    ): Int? {
        val activeDecades = availableDecades.sorted().filter { decade -> (weights[decade] ?: 0) > 0 }
        val totalWeight = activeDecades.sumOf { decade -> weights[decade] ?: 0 }
        if (totalWeight == 0 || randomRoll !in 0 until totalWeight) return null

        var remaining = randomRoll
        for (decade in activeDecades) {
            remaining -= weights[decade] ?: 0
            if (remaining < 0) return decade
        }
        return activeDecades.lastOrNull()
    }

    fun getAvailableYears(): List<Int> = allYears
    fun size(): Int = cachedVideos.size

    // ── Internal helpers ────────────────────────────────────────────────────

    private fun setCache(videos: List<VideoEntry>) {
        cachedVideos = videos
        yearIndex = videos.groupBy { it.year }
        allYears = yearIndex.keys.sorted()
    }

    /**
     * Extract YouTube video IDs from a blob of user input.
     * Handles:
     *   - https://www.youtube.com/watch?v=VIDEO_ID
     *   - https://youtu.be/VIDEO_ID
     *   - https://youtube.com/shorts/VIDEO_ID
     *   - https://www.youtube.com/embed/VIDEO_ID
     *   - Bare IDs: dQw4w9WgXcQ
     *   - Comma-separated, newline-separated, mixed
     */
    fun parseVideoIds(raw: String): List<String> {
        val ids = mutableListOf<String>()

        // Pattern 1: Full YouTube URLs — extract the video ID from query param or path
        val urlPattern = Pattern.compile(
            "(?:https?://)?(?:www\\.)?(?:youtube\\.com/(?:watch\\?v=|embed/|v/|shorts/)|youtu\\.be/)" +
            "([a-zA-Z0-9_-]{11})"
        )
        val urlMatcher = urlPattern.matcher(raw)
        while (urlMatcher.find()) {
            urlMatcher.group(1)?.let { ids.add(it) }
        }

        // Pattern 2: Bare 11-char video IDs (alphanumeric + - and _)
        // Only match if they look like standalone IDs (not part of URLs already matched)
        val barePattern = Pattern.compile("\\b([a-zA-Z0-9_-]{11})\\b")
        val bareMatcher = barePattern.matcher(raw)
        while (bareMatcher.find()) {
            val candidate = bareMatcher.group(1)!!
            // Skip if this position was already matched as a URL
            val isInUrl = urlMatcher.run {
                // Re-find to check encapsulation
                useTransparentBounds(true)
                reset()
                var found = false
                while (find()) {
                    if (start() <= bareMatcher.start() && end() >= bareMatcher.end()) {
                        found = true
                        break
                    }
                }
                found
            }
            if (!isInUrl) {
                ids.add(candidate)
            }
        }

        return ids.distinct()
    }
}
