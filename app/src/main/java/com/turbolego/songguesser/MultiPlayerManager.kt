package com.turbolego.songguesser

/**
 * Manages same-device multiplayer — multiple players on one screen.
 * Each player gets their own name, guess, and score.
 */
object MultiPlayerManager {

    data class Player(
        val name: String,
        var guessedYear: Int? = null,
        var score: Int = 0,
        var guessResult: ScoreManager.GuessResult? = null,
    )

    private val players: MutableList<Player> = mutableListOf()
    private var currentPlayerIndex = 0

    val playerCount: Int get() = players.size
    val allPlayers: List<Player> get() = players.toList()

    fun addPlayer(name: String): Boolean {
        if (players.size >= GamePreferences.MAX_PLAYERS) return false
        if (players.any { it.name.equals(name, ignoreCase = true) }) return false
        if (name.isBlank()) return false
        players.add(Player(name))
        return true
    }

    fun removePlayer(name: String): Boolean {
        val removed = players.removeAll { it.name.equals(name, ignoreCase = true) }
        if (currentPlayerIndex >= players.size) currentPlayerIndex = 0
        return removed
    }

    fun recordGuess(playerName: String, guessedYear: Int, actualYear: Int, difficulty: Difficulty) {
        val player = players.find { it.name.equals(playerName, ignoreCase = true) } ?: return
        val result = ScoreManager.evaluateGuess(guessedYear, actualYear, difficulty)
        player.guessedYear = guessedYear
        player.score += result.pointsEarned
        player.guessResult = result
    }

    fun getCurrentPlayerName(): String =
        if (players.isEmpty()) "" else players[currentPlayerIndex].name

    fun getCurrentPlayer(): Player? =
        if (players.isEmpty()) null else players[currentPlayerIndex]

    fun nextTurn(): String {
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size
        return getCurrentPlayerName()
    }

    fun getLeaderboard(): List<Player> = players.sortedByDescending { it.score }

    fun reset() {
        for (p in players) {
            p.score = 0
            p.guessedYear = null
            p.guessResult = null
        }
        currentPlayerIndex = 0
    }

    fun clear() {
        players.clear()
        currentPlayerIndex = 0
    }
}