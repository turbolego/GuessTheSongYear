package com.turbolego.songguesser

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ScoreManager.
 */
class ScoreManagerTest {

    @Before
    fun setUp() {
        ScoreManager.reset()
    }

    @Test
    fun `exact guess on Medium should award 75 points`() {
        val result = ScoreManager.evaluateGuess(2000, 2000, Difficulty.MEDIUM)
        assertEquals(75, result.pointsEarned)
        assertTrue(result.isCorrect)
    }

    @Test
    fun `off by 1 on Easy should award 30 points`() {
        val result = ScoreManager.evaluateGuess(1989, 1990, Difficulty.EASY)
        assertEquals(30, result.pointsEarned)
        assertTrue(result.isCorrect)
    }

    @Test
    fun `off by 5 on Easy should award 10 points`() {
        val result = ScoreManager.evaluateGuess(1995, 1990, Difficulty.EASY)
        assertEquals(10, result.pointsEarned)
        assertFalse(result.isCorrect)
    }

    @Test
    fun `streak resets on wrong guess`() {
        ScoreManager.evaluateGuess(1990, 1990, Difficulty.EASY) // 1
        ScoreManager.evaluateGuess(1990, 2000, Difficulty.EASY) // 0
        assertEquals(0, ScoreManager.streak)
    }

    @Test
    fun `total score accumulates`() {
        ScoreManager.evaluateGuess(1990, 1990, Difficulty.EASY)
        ScoreManager.evaluateGuess(1989, 1990, Difficulty.EASY)
        assertEquals(80, ScoreManager.score)
    }

    @Test
    fun `reset clears all state`() {
        ScoreManager.evaluateGuess(1990, 1990, Difficulty.EASY)
        ScoreManager.reset()
        assertEquals(0, ScoreManager.score)
        assertEquals(0, ScoreManager.streak)
    }
}