package com.turbolego.songguesser

/**
 * Manages game sessions for local multiplayer and remote multiplayer networking.
 *
 * GameSession tracks players, scores, current video, turn state, and
 * blind guesses that arrive before a REVEAL_RESULT is computed.
 */
class GameSessionManager {

    /**
     * A blind guess submitted by a remote client before the reveal round.
     * The host stores these without broadcasting to other clients.
     */
    data class BlindGuess(
        val playerName: String,
        val guessedYear: Int
    )

    data class RevealResult(
        val playerName: String,
        val guess: Int,
        val correctYear: Int,
        val pointsEarned: Int,
        val difference: Int,
        val isCorrect: Boolean,
        val totalScore: Int
    )

    data class GameSession(
        var sessionId: String,
        var hostName: String,
        var players: MutableMap<String, PlayerInfo>,
        var currentVideoId: String? = null,
        var currentYear: Int? = null,
        var currentTitle: String? = null,
        var currentPlayerIndex: Int = 0,
        var isHost: Boolean = false,
        /** Blind (unbroadcasted) guesses keyed by player name. Cleared after reveal. */
        val blindGuesses: MutableMap<String, BlindGuess> = mutableMapOf()
    ) {
        data class PlayerInfo(
            var name: String,
            var score: Int = 0,
            var isHost: Boolean = false
        )
    }

    private val sessions = mutableMapOf<String, GameSession>()

    private val listeners = mutableListOf<SessionListener>()

    interface SessionListener {
        fun onSessionCreated(session: GameSession)
        fun onSessionJoined(session: GameSession)
        fun onPlayerAdded(session: GameSession, player: GameSession.PlayerInfo)
        fun onPlayerRemoved(session: GameSession, playerName: String)
        fun onVideoChanged(session: GameSession, videoId: String?, year: Int?, title: String?)
        fun onTurnChanged(session: GameSession, currentPlayerIndex: Int)
        fun onScoreUpdated(session: GameSession, playerName: String, score: Int)
        fun onSessionEnded(session: GameSession)
    }

    fun addListener(listener: SessionListener) {
        listeners.add(listener)
    }

    private fun notifyListeners(action: (SessionListener) -> Unit) {
        listeners.forEach { action(it) }
    }

    /**
     * Create a new game session
     */
    fun createSession(sessionId: String, hostName: String): GameSession {
        val session = GameSession(
            sessionId = sessionId,
            hostName = hostName,
            players = mutableMapOf("HOST" to GameSession.PlayerInfo(hostName, isHost = true)),
            isHost = true
        )
        sessions[sessionId] = session
        notifyListeners { it.onSessionCreated(session) }
        return session
    }

    /**
     * Join an existing game session
     */
    fun joinSession(sessionId: String, playerName: String): GameSession? {
        val session = sessions[sessionId]
        if (session == null) return null

        session.players[playerName] = GameSession.PlayerInfo(playerName)
        notifyListeners { it.onSessionJoined(session) }
        notifyListeners { it.onPlayerAdded(session, session.players[playerName]!!) }
        return session
    }

    /**
     * Leave a game session
     */
    fun leaveSession(sessionId: String, playerName: String) {
        val session = sessions[sessionId]
        if (session == null) return

        session.players.remove(playerName)
        session.blindGuesses.remove(playerName)
        notifyListeners { it.onPlayerRemoved(session, playerName) }

        if (session.players.isEmpty()) {
            sessions.remove(sessionId)
            notifyListeners { it.onSessionEnded(session) }
        }
    }

    /**
     * Set the current video for the game
     */
    fun setVideo(sessionId: String, videoId: String, year: Int, title: String) {
        val session = sessions[sessionId]
        if (session == null) return

        session.currentVideoId = videoId
        session.currentYear = year
        session.currentTitle = title
        // Clear previous round's blind guesses when a new video loads
        session.blindGuesses.clear()
        notifyListeners { it.onVideoChanged(session, videoId, year, title) }
    }

    /**
     * Advance to the next player's turn
     */
    fun nextTurn(sessionId: String): Int {
        val session = sessions[sessionId]
        if (session == null) return 0

        val currentPlayerIndex = (session.currentPlayerIndex + 1) % session.players.size
        session.currentPlayerIndex = currentPlayerIndex
        notifyListeners { it.onTurnChanged(session, currentPlayerIndex) }
        return currentPlayerIndex
    }

    /**
     * Get the current player's name
     */
    fun getCurrentPlayer(sessionId: String): String? {
        val session = sessions[sessionId]
        return if (session != null) {
            session.players.keys.elementAt(session.currentPlayerIndex)
        } else null
    }

    /**
     * Update player score
     */
    fun updateScore(sessionId: String, playerName: String, score: Int) {
        val session = sessions[sessionId]
        if (session == null) return

        val player = session.players[playerName]
        if (player != null) {
            player.score = score
            notifyListeners { it.onScoreUpdated(session, playerName, score) }
        }
    }

    /**
     * Get all players in a session
     */
    fun getPlayers(sessionId: String): List<GameSession.PlayerInfo> {
        val session = sessions[sessionId]
        return session?.players?.values?.toList() ?: emptyList()
    }

    /**
     * Get session by ID
     */
    fun getSession(sessionId: String): GameSession? = sessions[sessionId]

    /**
     * Get all active sessions
     */
    fun getAllSessions(): List<GameSession> = sessions.values.toList()

    /**
     * Remove a session
     */
    fun removeSession(sessionId: String) {
        val session = sessions.remove(sessionId)
        if (session != null) {
            notifyListeners { it.onSessionEnded(session) }
        }
    }

    /**
     * Check if a session exists
     */
    fun hasSession(sessionId: String): Boolean = sessions.containsKey(sessionId)

    /**
     * Check if player is host in a session
     */
    fun isHost(sessionId: String, playerName: String): Boolean {
        val session = sessions[sessionId]
        return session?.players?.get(playerName)?.isHost == true
    }

    /**
     * Get the host name for a session
     */
    fun getHostName(sessionId: String): String? {
        val session = sessions[sessionId]
        return session?.hostName
    }

    // ── Blind Guess Methods ──────────────────────────────────────────────────

    /**
     * Store a blind guess from a player. These are NOT broadcasted to other clients
     * and stored privately on the host until [computeRevealResults] is called.
     *
     * @return true if the guess was stored, false if session or player not found
     */
    fun storeBlindGuess(sessionId: String, playerName: String, guessedYear: Int): Boolean {
        val session = sessions[sessionId] ?: return false
        if (!session.players.containsKey(playerName)) return false
        session.blindGuesses[playerName] = BlindGuess(playerName, guessedYear)
        return true
    }

    /**
     * Retrieve all blind guesses for a session.
     * Returns a snapshot of the guesses map at the time of the call.
     */
    fun getBlindGuesses(sessionId: String): Map<String, BlindGuess> {
        val session = sessions[sessionId] ?: return emptyMap()
        return session.blindGuesses.toMap()
    }

    /**
     * Get the count of blind guesses currently stored for a session.
     */
    fun blindGuessCount(sessionId: String): Int {
        val session = sessions[sessionId] ?: return 0
        return session.blindGuesses.size
    }

    /**
     * Clear all blind guesses for a session (e.g., after reveal, or on new video).
     */
    fun clearBlindGuesses(sessionId: String) {
        val session = sessions[sessionId]
        session?.blindGuesses?.clear()
    }

    /**
     * Compute reveal results from stored blind guesses.
     *
     * Each blind guess is evaluated against the session's current video year.
     * Scores are updated and a list of [RevealResult] is returned.
     * Blind guesses are NOT cleared after this — call [clearBlindGuesses] explicitly.
     *
     * @return ordered list of reveal results (one per blind guess stored)
     */
    fun computeRevealResults(
        sessionId: String,
        difficulty: Difficulty = Difficulty.MEDIUM
    ): List<RevealResult> {
        val session = sessions[sessionId] ?: return emptyList()
        val correctYear = session.currentYear ?: return emptyList()

        val results = mutableListOf<RevealResult>()

        for ((playerName, guess) in session.blindGuesses) {
            val result = ScoreManager.evaluateGuess(guess.guessedYear, correctYear, difficulty)
            val player = session.players[playerName]
            val newTotalScore = (player?.score ?: 0) + result.pointsEarned

            if (player != null) {
                player.score = newTotalScore
                notifyListeners { it.onScoreUpdated(session, playerName, newTotalScore) }
            }

            results.add(
                RevealResult(
                    playerName = playerName,
                    guess = guess.guessedYear,
                    correctYear = correctYear,
                    pointsEarned = result.pointsEarned,
                    difference = result.difference,
                    isCorrect = result.isCorrect,
                    totalScore = newTotalScore
                )
            )
        }

        return results
    }
}
