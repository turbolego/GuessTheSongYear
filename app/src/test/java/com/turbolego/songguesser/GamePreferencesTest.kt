package com.turbolego.songguesser

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun `default decade distributions always total one hundred percent`() {
        assertEquals(100, GamePreferences.defaultWeights(RandomizationMode.PURE_RANDOM).values.sum())
        assertEquals(100, GamePreferences.defaultWeights(RandomizationMode.PRIORITIZE_MODERN).values.sum())
    }

    @Test
    fun `custom randomization selects a decade by weight before picking a year`() {
        val weights = mapOf(1960 to 40, 1970 to 60)
        val availableDecades = listOf(1960, 1970)

        assertEquals(1960, VideoProvider.selectCustomWeightedDecade(weights, availableDecades, 0))
        assertEquals(1960, VideoProvider.selectCustomWeightedDecade(weights, availableDecades, 39))
        assertEquals(1970, VideoProvider.selectCustomWeightedDecade(weights, availableDecades, 40))
        assertEquals(1970, VideoProvider.selectCustomWeightedDecade(weights, availableDecades, 99))
    }

    @Test
    fun `history rollover keeps the new song and drops the oldest song`() {
        val history = (1..PlayHistory.MAX_ENTRIES).map { "video-$it" }

        val updated = PlayHistory.appendToHistory(history, "new-video")

        assertEquals(PlayHistory.MAX_ENTRIES, updated.size)
        assertFalse(updated.contains("video-1"))
        assertEquals("new-video", updated.last())
    }

    @Test
    fun `history replay refreshes the song recency rather than dropping it`() {
        val history = listOf("first", "middle", "last")

        val updated = PlayHistory.appendToHistory(history, "first")

        assertEquals(listOf("middle", "last", "first"), updated)
    }

    @Test
    fun `player-stat storage keys do not merge punctuation variants`() {
        assertNotEquals(
            PlayerStatistics.storageKeyFor("A-B"),
            PlayerStatistics.storageKeyFor("A B"),
        )
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
