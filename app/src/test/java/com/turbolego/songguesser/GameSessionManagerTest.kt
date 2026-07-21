package com.turbolego.songguesser

import org.junit.Assert.*
import org.junit.Test

class GameSessionManagerTest {

    @Test
    fun `create session should add host player`() {
        val manager = GameSessionManager()
        val session = manager.createSession("test123", "HostPlayer")

        assertNotNull(session)
        assertEquals("test123", session.sessionId)
        assertEquals("HostPlayer", session.hostName)
        assertEquals(1, session.players.size)
        assertTrue(session.isHost)
    }

    @Test
    fun `create session should notify listeners`() {
        val manager = GameSessionManager()
        var sessionCreated = false
        val listener = object : GameSessionManager.SessionListener {
            override fun onSessionCreated(session: GameSessionManager.GameSession) {
                sessionCreated = true
            }
            override fun onSessionJoined(session: GameSessionManager.GameSession) {}
            override fun onPlayerAdded(session: GameSessionManager.GameSession, player: GameSessionManager.GameSession.PlayerInfo) {}
            override fun onPlayerRemoved(session: GameSessionManager.GameSession, playerName: String) {}
            override fun onVideoChanged(session: GameSessionManager.GameSession, videoId: String?, year: Int?, title: String?) {}
            override fun onTurnChanged(session: GameSessionManager.GameSession, currentPlayerIndex: Int) {}
            override fun onScoreUpdated(session: GameSessionManager.GameSession, playerName: String, score: Int) {}
            override fun onSessionEnded(session: GameSessionManager.GameSession) {}
        }
        manager.addListener(listener)
        manager.createSession("test123", "HostPlayer")

        assertTrue(sessionCreated)
    }

    @Test
    fun `join session should add player`() {
        val manager = GameSessionManager()
        manager.createSession("test123", "HostPlayer")
        val session = manager.joinSession("test123", "Player1")!!

        assertEquals(2, session.players.size)
        // Host is the only one with isHost = true
        val hostPlayer = session.players["HOST"]
        assertNotNull(hostPlayer)
        assertTrue(hostPlayer!!.isHost)
    }

    @Test
    fun `join non-existent session should return null`() {
        val manager = GameSessionManager()
        val session = manager.joinSession("nonexistent", "Player1")

        assertNull(session)
    }

    @Test
    fun `leave session should remove player`() {
        val manager = GameSessionManager()
        manager.createSession("test123", "HostPlayer")
        manager.joinSession("test123", "Player1")
        manager.leaveSession("test123", "Player1")

        val session = manager.getSession("test123")!!
        assertEquals(1, session.players.size)
    }

    @Test
    fun `leave last player should end session`() {
        val manager = GameSessionManager()
        manager.createSession("test123", "HostPlayer")
        manager.joinSession("test123", "Player1")
        manager.leaveSession("test123", "Player1")
        // Now leave the host to end the session
        manager.leaveSession("test123", "HOST")

        assertNull(manager.getSession("test123"))
    }

    @Test
    fun `set video should update session video info`() {
        val manager = GameSessionManager()
        manager.createSession("test123", "HostPlayer")
        manager.setVideo("test123", "dQw4w9WgXcQ", 1987, "Never Gonna Give You Up")

        val session = manager.getSession("test123")!!
        assertEquals("dQw4w9WgXcQ", session.currentVideoId)
        assertEquals(1987, session.currentYear)
        assertEquals("Never Gonna Give You Up", session.currentTitle)
    }

    @Test
    fun `next turn should advance to next player`() {
        val manager = GameSessionManager()
        manager.createSession("test123", "HostPlayer")
        manager.joinSession("test123", "Player1")
        manager.joinSession("test123", "Player2")

        val currentPlayerIndex = manager.nextTurn("test123")
        assertEquals(1, currentPlayerIndex)
    }

    @Test
    fun `get current player should return correct player name`() {
        val manager = GameSessionManager()
        manager.createSession("test123", "HostPlayer")
        manager.joinSession("test123", "Player1")
        manager.joinSession("test123", "Player2")
        manager.nextTurn("test123")

        val currentPlayer = manager.getCurrentPlayer("test123")
        assertEquals("Player1", currentPlayer)
    }

    @Test
    fun `update score should update player score`() {
        val manager = GameSessionManager()
        manager.createSession("test123", "HostPlayer")
        manager.joinSession("test123", "Player1")

        manager.updateScore("test123", "Player1", 100)

        val players = manager.getPlayers("test123")
        // Find Player1 in the list
        val player1 = players.find { it.name == "Player1" }
        assertNotNull(player1)
        assertEquals(100, player1!!.score)
    }

    @Test
    fun `get all sessions should return active sessions`() {
        val manager = GameSessionManager()
        manager.createSession("test1", "Host1")
        manager.createSession("test2", "Host2")

        val sessions = manager.getAllSessions()
        assertEquals(2, sessions.size)
    }

    @Test
    fun `remove session should clear session from map`() {
        val manager = GameSessionManager()
        manager.createSession("test123", "HostPlayer")
        manager.removeSession("test123")

        assertNull(manager.getSession("test123"))
    }

    @Test
    fun `has session should return correct boolean`() {
        val manager = GameSessionManager()
        manager.createSession("test123", "HostPlayer")

        assertTrue(manager.hasSession("test123"))
        assertFalse(manager.hasSession("nonexistent"))
    }

    @Test
    fun `is host should return correct boolean`() {
        val manager = GameSessionManager()
        manager.createSession("test123", "MyHost")

        // The host is stored with key "HOST"
        assertTrue(manager.isHost("test123", "HOST"))
        assertFalse(manager.isHost("test123", "Player1"))
    }

    @Test
    fun `get host name should return correct name`() {
        val manager = GameSessionManager()
        manager.createSession("test123", "MyHost")

        assertEquals("MyHost", manager.getHostName("test123"))
    }
}