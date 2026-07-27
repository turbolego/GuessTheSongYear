package com.turbolego.songguesser

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ScoreManager — points, streaks, accuracy.
 */
class ScoreManagerTest {

    @Before
    fun setUp() {
        ScoreManager.reset()
    }

    @Test
    fun `exact guess on Easy should award 55 points because streak incremented first`() {
        val result = ScoreManager.evaluateGuess(1990, 1990, Difficulty.EASY)
        // Code does: currentStreak++ BEFORE points = 50 * mult * (1 + streak * 0.1)
        // So first guess: 50 * 1.0 * (1 + 1 * 0.1) = 55
        assertEquals(55, result.pointsEarned)
        assertTrue(result.isCorrect)
    }

    @Test
    fun `exact guess on Medium should award 82 points`() {
        val result = ScoreManager.evaluateGuess(2000, 2000, Difficulty.MEDIUM)
        // 50 * 1.5 * (1 + 1 * 0.1) = 82.5 → 82
        assertEquals(82, result.pointsEarned)
        assertTrue(result.isCorrect)
    }

    @Test
    fun `off by 1 should award 30 points`() {
        val result = ScoreManager.evaluateGuess(1989, 1990, Difficulty.EASY)
        assertEquals(30, result.pointsEarned)
        assertTrue(result.isCorrect)
    }

    @Test
    fun `off by 3 should award 20 points`() {
        val result = ScoreManager.evaluateGuess(1993, 1990, Difficulty.EASY)
        assertEquals(20, result.pointsEarned)
        assertTrue(result.isCorrect)
    }

    @Test
    fun `off by 5 should award 10 points`() {
        val result = ScoreManager.evaluateGuess(1995, 1990, Difficulty.EASY)
        assertEquals(10, result.pointsEarned)
        assertFalse(result.isCorrect)
    }

    @Test
    fun `off by 10 should award 5 points`() {
        val result = ScoreManager.evaluateGuess(2000, 1990, Difficulty.EASY)
        assertEquals(5, result.pointsEarned)
        assertFalse(result.isCorrect)
    }

    @Test
    fun `off by 15 should award 0 points`() {
        val result = ScoreManager.evaluateGuess(2005, 1990, Difficulty.EASY)
        assertEquals(0, result.pointsEarned)
        assertFalse(result.isCorrect)
    }

    @Test
    fun `streak builds on consecutive exact guesses`() {
        ScoreManager.evaluateGuess(1990, 1990, Difficulty.EASY)
        assertEquals(1, ScoreManager.streak)
        ScoreManager.evaluateGuess(1995, 1995, Difficulty.EASY)
        assertEquals(2, ScoreManager.streak)
    }

    @Test
    fun `streak resets on off-by-more-than-3`() {
        ScoreManager.evaluateGuess(1990, 1990, Difficulty.EASY)
        assertEquals(1, ScoreManager.streak)
        ScoreManager.evaluateGuess(1995, 1990, Difficulty.EASY)
        assertEquals(0, ScoreManager.streak)
    }

    @Test
    fun `total score accumulates correctly`() {
        ScoreManager.evaluateGuess(1990, 1990, Difficulty.EASY) // 55 (with streak=1 bonus)
        ScoreManager.evaluateGuess(1989, 1990, Difficulty.EASY) // 30
        assertEquals(85, ScoreManager.score)
    }

    @Test
    fun `reset clears all state`() {
        ScoreManager.evaluateGuess(1990, 1990, Difficulty.EASY)
        assertTrue(ScoreManager.score > 0)
        ScoreManager.reset()
        assertEquals(0, ScoreManager.score)
        assertEquals(0, ScoreManager.streak)
        assertEquals(0, ScoreManager.guessCount)
        assertEquals(0f, ScoreManager.accuracy, 0.001f)
    }

    @Test
    fun `off by 1 should trigger streak`() {
        ScoreManager.reset()
        ScoreManager.evaluateGuess(1989, 1990, Difficulty.EASY)
        assertEquals(1, ScoreManager.streak)
    }

    @Test
    fun `highest streak tracked across resets`() {
        ScoreManager.evaluateGuess(1990, 1990, Difficulty.EASY) // streak=1
        ScoreManager.evaluateGuess(1991, 1991, Difficulty.EASY) // streak=2
        ScoreManager.evaluateGuess(2000, 1990, Difficulty.EASY) // streak=0
        assertEquals(0, ScoreManager.streak)
        assertEquals(2, ScoreManager.highStreak)
    }
}