package com.turbolego.songguesser

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePreferencesTest {

    @After
    fun resetPlayers() {
        MultiPlayerManager.clear()
    }

    @Test
    fun `custom decade redistribution preserves selected value and total`() {
        val initial = GamePreferences.decadeStarts().associateWith { 100 / GamePreferences.decadeStarts().size }

        val redistributed = GamePreferences.redistributeWeights(
            weights = initial,
            selectedDecade = 2000,
            selectedWeight = 42,
        )

        assertEquals(42, redistributed[2000])
        assertEquals(100, redistributed.values.sum())
        assertTrue(redistributed.values.all { it >= 0 })
    }

    @Test
    fun `arcade player turn rotates and wraps`() {
        assertTrue(MultiPlayerManager.addPlayer("Alex"))
        assertTrue(MultiPlayerManager.addPlayer("Blair"))
        assertTrue(MultiPlayerManager.addPlayer("Casey"))

        assertEquals("Alex", MultiPlayerManager.getCurrentPlayerName())
        assertEquals("Blair", MultiPlayerManager.nextTurn())
        assertEquals("Casey", MultiPlayerManager.nextTurn())
        assertEquals("Alex", MultiPlayerManager.nextTurn())
    }

    @Test
    fun `party configuration does not exceed eight players`() {
        repeat(GamePreferences.MAX_PLAYERS) { index ->
            assertTrue(MultiPlayerManager.addPlayer("Player$index"))
        }

        assertFalse(MultiPlayerManager.addPlayer("Overflow"))
        assertEquals(GamePreferences.MAX_PLAYERS, MultiPlayerManager.playerCount)
    }
}
