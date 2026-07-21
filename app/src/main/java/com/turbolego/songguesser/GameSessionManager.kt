package com.turbolego.songguesser

/**
 * Manages game sessions for local multiplayer.
 */
class GameSessionManager {
    
    data class GameSession(
        var sessionId: String,
        var hostName: String,
        var players: MutableMap<String, PlayerInfo>,
        var currentVideoId: String? = null,
        var currentYear: Int? = null,
        var currentTitle: String? = null,
        var currentPlayerIndex: Int = 0,
        var isHost: Boolean = false
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
        notifyListeners { it.onVideoChanged(session, videoId, year, title) }
    }
    
    // getCurrentVideo removed - functionality moved to util.YouTubeApiServiceImpl
    
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
}
