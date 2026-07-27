package com.turbolego.songguesser

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for Difficulty enum.
 */
class DifficultyTest {

    @Test
    fun `Easy range is 1980-2010`() {
        assertEquals(1980, Difficulty.EASY.yearRangeStart)
        assertEquals(2010, Difficulty.EASY.yearRangeEnd)
    }

    @Test
    fun `Medium range is 1970-2024`() {
        assertEquals(1970, Difficulty.MEDIUM.yearRangeStart)
        assertEquals(2024, Difficulty.MEDIUM.yearRangeEnd)
    }

    @Test
    fun `Hard range is 1960-2025`() {
        assertEquals(1960, Difficulty.HARD.yearRangeStart)
        assertEquals(2025, Difficulty.HARD.yearRangeEnd)
    }

    @Test
    fun `Easy multiplier is 1.0`() {
        assertEquals(1.0f, Difficulty.EASY.pointMultiplier)
    }

    @Test
    fun `Medium multiplier is 1.5`() {
        assertEquals(1.5f, Difficulty.MEDIUM.pointMultiplier)
    }

    @Test
    fun `Hard multiplier is 2.5`() {
        assertEquals(2.5f, Difficulty.HARD.pointMultiplier)
    }

    @Test
    fun `Easy has hints enabled`() {
        assertTrue(Difficulty.EASY.hintEnabled)
    }

    @Test
    fun `Medium has hints disabled`() {
        assertFalse(Difficulty.MEDIUM.hintEnabled)
    }

    @Test
    fun `Hard has hints disabled`() {
        assertFalse(Difficulty.HARD.hintEnabled)
    }
}