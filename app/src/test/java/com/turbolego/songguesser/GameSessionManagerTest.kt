package com.turbolego.songguesser

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for GameSessionManager — session lifecycle, blind guesses, reveals.
 */
class GameSessionManagerTest {

    private lateinit var manager: GameSessionManager

    @Before
    fun setUp() {
        manager = GameSessionManager()
    }

    @Test
    fun `create session should set host and session ID`() {
        val session = manager.createSession("test123", "HostPlayer")
        assertNotNull(session)
        assertEquals("test123", session.sessionId)
        assertEquals("HostPlayer", session.hostName)
        assertTrue(session.isHost)
    }

    @Test
    fun `join session should add player to session`() {
        manager.createSession("test123", "HostPlayer")
        val session = manager.joinSession("test123", "Player1")
        assertNotNull(session)
        assertEquals(2, session!!.players.size)
        assertTrue(session.players.containsKey("Player1"))
    }

    @Test
    fun `join non-existent session returns null`() {
        val session = manager.joinSession("nonexistent", "Player1")
        assertNull(session)
    }

    @Test
    fun `leave session removes player from session`() {
        manager.createSession("test123", "HostPlayer")
        manager.joinSession("test123", "Player1")
        manager.leaveSession("test123", "Player1")

        val session = manager.getSession("test123")
        assertNotNull(session)
        assertEquals(1, session!!.players.size)
        assertFalse(session.players.containsKey("Player1"))
    }

    @Test
    fun `leave last player ends session`() {
        manager.createSession("test123", "HostPlayer")
        manager.joinSession("test123", "Player1")
        manager.leaveSession("test123", "Player1")
        manager.leaveSession("test123", "HOST")

        assertNull(manager.getSession("test123"))
    }

    @Test
    fun `set video updates session video info`() {
        manager.createSession("test123", "HostPlayer")
        manager.setVideo("test123", "dQw4w9WgXcQ", 1987, "Never Gonna Give You Up")

        val session = manager.getSession("test123")
        assertNotNull(session)
        assertEquals("dQw4w9WgXcQ", session!!.currentVideoId)
        assertEquals(1987, session.currentYear)
        assertEquals("Never Gonna Give You Up", session.currentTitle)
    }

    @Test
    fun `nextTurn advances to next player`() {
        manager.createSession("test123", "HostPlayer")
        manager.joinSession("test123", "Player1")
        manager.joinSession("test123", "Player2")

        val currentIndex = manager.nextTurn("test123")
        assertEquals(1, currentIndex)
    }

    @Test
    fun `getCurrentPlayer returns correct player name`() {
        manager.createSession("test123", "HostPlayer")
        manager.joinSession("test123", "Player1")
        manager.joinSession("test123", "Player2")
        manager.nextTurn("test123")

        val currentPlayer = manager.getCurrentPlayer("test123")
        assertEquals("Player1", currentPlayer)
    }

    @Test
    fun `updateScore updates player score`() {
        manager.createSession("test123", "HostPlayer")
        manager.joinSession("test123", "Player1")

        manager.updateScore("test123", "Player1", 100)

        val players = manager.getPlayers("test123")
        val player1 = players.find { it.name == "Player1" }
        assertNotNull(player1)
        assertEquals(100, player1!!.score)
    }

    @Test
    fun `getAllSessions returns active sessions`() {
        manager.createSession("session1", "Host1")
        manager.createSession("session2", "Host2")

        val sessions = manager.getAllSessions()
        assertEquals(2, sessions.size)
    }

    @Test
    fun `remove session should clear session from map`() {
        manager.createSession("test123", "HostPlayer")
        manager.removeSession("test123")

        assertNull(manager.getSession("test123"))
    }

    @Test
    fun `has session should return correct boolean`() {
        manager.createSession("test123", "HostPlayer")

        assertTrue(manager.hasSession("test123"))
        assertFalse(manager.hasSession("nonexistent"))
    }

    @Test
    fun `is host should return correct boolean`() {
        manager.createSession("test123", "MyHost")

        assertTrue(manager.isHost("test123", "HOST"))
        assertFalse(manager.isHost("test123", "Player1"))
    }

    @Test
    fun `get host name should return correct name`() {
        manager.createSession("test123", "MyHost")

        assertEquals("MyHost", manager.getHostName("test123"))
    }

    @Test
    fun `storeBlindGuess saves guess for later reveal`() {
        manager.createSession("test123", "Host")
        manager.joinSession("test123", "Alice")

        val result = manager.storeBlindGuess("test123", "Alice", 1995)
        assertTrue(result)

        val guesses = manager.getBlindGuesses("test123")
        assertEquals(1, guesses.size)
        val guess = guesses["Alice"]
        assertNotNull(guess)
        assertEquals("Alice", guess!!.playerName)
        assertEquals(1995, guess.guessedYear)
    }

    @Test
    fun `storeBlindGuess fails for non-existent session`() {
        val result = manager.storeBlindGuess("nonexistent", "Player", 1995)
        assertFalse(result)
    }

    @Test
    fun `storeBlindGuess fails for non-existent player`() {
        manager.createSession("test123", "Host")
        val result = manager.storeBlindGuess("test123", "UnknownPlayer", 1995)
        assertFalse(result)
    }

    @Test
    fun `clearBlindGuesses clears session guesses`() {
        manager.createSession("test123", "Host")
        manager.joinSession("test123", "Alice")
        manager.storeBlindGuess("test123", "Alice", 1995)

        assertEquals(1, manager.blindGuessCount("test123"))
        manager.clearBlindGuesses("test123")
        assertEquals(0, manager.blindGuessCount("test123"))
    }

    @Test
    fun `computeRevealResults evaluates guesses correctly`() {
        manager.createSession("test123", "Host")
        manager.joinSession("test123", "Alice")
        manager.joinSession("test123", "Bob")

        manager.setVideo("test123", "test", 2000, "Test")
        manager.storeBlindGuess("test123", "Alice", 1995)
        manager.storeBlindGuess("test123", "Bob", 2000)

        val results = manager.computeRevealResults("test123", Difficulty.EASY)
        assertEquals(2, results.size)

        val alice = results.first { it.playerName == "Alice" }
        assertEquals(10, alice.pointsEarned)
        assertEquals(5, alice.difference)
        assertFalse(alice.isCorrect)

        val bob = results.first { it.playerName == "Bob" }
        assertEquals(55, bob.pointsEarned) // 50 * 1.0 * (1 + 1 * 0.1) = 55
        assertEquals(0, bob.difference)
        assertTrue(bob.isCorrect)
    }
}