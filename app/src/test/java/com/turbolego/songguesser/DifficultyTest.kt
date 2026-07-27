package com.turbolego.songguesser

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Difficulty enum — year ranges, point multipliers, hints.
 */
class DifficultyTest {

    @Test
    fun `Easy range is 1980 to 2010`() {
        assertEquals(1980, Difficulty.EASY.yearRangeStart)
        assertEquals(2010, Difficulty.EASY.yearRangeEnd)
    }

    @Test
    fun `Medium range is 1970 to 2024`() {
        assertEquals(1970, Difficulty.MEDIUM.yearRangeStart)
        assertEquals(2024, Difficulty.MEDIUM.yearRangeEnd)
    }

    @Test
    fun `Hard range is 1960 to 2025`() {
        assertEquals(1960, Difficulty.HARD.yearRangeStart)
        assertEquals(2025, Difficulty.HARD.yearRangeEnd)
    }

    @Test
    fun `easy point multiplier is 1`() {
        assertEquals(1.0f, Difficulty.EASY.pointMultiplier)
    }

    @Test
    fun `medium point multiplier is 1 point 5`() {
        assertEquals(1.5f, Difficulty.MEDIUM.pointMultiplier)
    }

    @Test
    fun `hard point multiplier is 2 point 5`() {
        assertEquals(2.5f, Difficulty.HARD.pointMultiplier)
    }

    @Test
    fun `easy has hints enabled`() {
        assertTrue(Difficulty.EASY.hintEnabled)
    }

    @Test
    fun `medium has hints disabled`() {
        assertFalse(Difficulty.MEDIUM.hintEnabled)
    }

    @Test
    fun `hard has hints disabled`() {
        assertFalse(Difficulty.HARD.hintEnabled)
    }

    @Test
    fun `difficulty progression is correct`() {
        assertTrue(Difficulty.HARD.pointMultiplier > Difficulty.MEDIUM.pointMultiplier)
        assertTrue(Difficulty.MEDIUM.pointMultiplier > Difficulty.EASY.pointMultiplier)
    }
}