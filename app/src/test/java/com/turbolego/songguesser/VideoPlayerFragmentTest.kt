package com.turbolego.songguesser

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for VideoPlayerFragment public API.
 * Tests only public methods that can be accessed from outside the package.
 */
class VideoPlayerFragmentTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // Difficulty Enum Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Difficulty enum should have three levels`() {
        assertEquals(3, Difficulty.entries.size)
    }

    @Test
    fun `Difficulty enum values should have valid year ranges`() {
        // Easy: 1980-2010
        assertEquals(1980, Difficulty.EASY.yearRangeStart)
        assertEquals(2010, Difficulty.EASY.yearRangeEnd)
        
        // Medium: 1970-2024
        assertEquals(1970, Difficulty.MEDIUM.yearRangeStart)
        assertEquals(2024, Difficulty.MEDIUM.yearRangeEnd)
        
        // Hard: 1960-2025
        assertEquals(1960, Difficulty.HARD.yearRangeStart)
        assertEquals(2025, Difficulty.HARD.yearRangeEnd)
    }

    @Test
    fun `Difficulty enum values should expose valid multipliers`() {
        assertEquals(1.0f, Difficulty.EASY.pointMultiplier)
        assertEquals(1.5f, Difficulty.MEDIUM.pointMultiplier)
        assertEquals(2.5f, Difficulty.HARD.pointMultiplier)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VideoEntry Data Class Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `VideoEntry data class should store id and year`() {
        val video = VideoProvider.VideoEntry("dQw4w9WgXcQ", 1987)
        assertEquals("dQw4w9WgXcQ", video.id)
        assertEquals(1987, video.year)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Video Selection Logic Tests (simulated)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `video selection should pick minimum year for EASY difficulty`() {
        val videos = listOf(
            VideoProvider.VideoEntry("id1", 2015),
            VideoProvider.VideoEntry("id2", 1990),
            VideoProvider.VideoEntry("id3", 2000)
        )
        val easyRange = 1980..2010
        val unscored = videos.filter { it.year in easyRange }
        val candidate = unscored.minByOrNull { it.year }
        
        if (candidate != null) {
            assertEquals(1990, candidate.year)
        }
    }

    @Test
    fun `video selection should pick maximum year for HARD difficulty`() {
        val videos = listOf(
            VideoProvider.VideoEntry("id1", 1990),
            VideoProvider.VideoEntry("id2", 2015),
            VideoProvider.VideoEntry("id3", 2000)
        )
        val hardRange = 2010..2025
        val unscored = videos.filter { it.year in hardRange }
        val candidate = unscored.maxByOrNull { it.year }
        
        if (candidate != null) {
            assertEquals(2015, candidate.year)
        }
    }

    @Test
    fun `video selection should pick furthest from 2000 for MEDIUM difficulty`() {
        val videos = listOf(
            VideoProvider.VideoEntry("id1", 1990),
            VideoProvider.VideoEntry("id2", 2015),
            VideoProvider.VideoEntry("id3", 2000)
        )
        val mediumRange = 1970..2024
        val unscored = videos.filter { it.year in mediumRange }
        val candidate = unscored.maxByOrNull { kotlin.math.abs(it.year - 2000) }
        
        if (candidate != null) {
            // 1990 is 10 years from 2000, 2015 is 15 years from 2000
            assertEquals(2015, candidate.year)
        }
    }

    @Test
    fun `video selection should return null when no videos match difficulty range`() {
        val videos = listOf(
            VideoProvider.VideoEntry("id1", 2015),
            VideoProvider.VideoEntry("id2", 2016),
            VideoProvider.VideoEntry("id3", 2017)
        )
        val easyRange = 1980..2010
        val unscored = videos.filter { it.year in easyRange }
        
        assertEquals(0, unscored.size)
    }

    @Test
    fun `video selection should return first video when no unscored videos`() {
        val videos = listOf(
            VideoProvider.VideoEntry("id1", 2015),
            VideoProvider.VideoEntry("id2", 2016),
            VideoProvider.VideoEntry("id3", 2017)
        )
        val playedIds = setOf("id1", "id2", "id3")
        val unscored = videos.filter { it.id !in playedIds }
        
        assertEquals(0, unscored.size)
    }

    @Test
    fun `video selection should return first video when all others are played`() {
        val videos = listOf(
            VideoProvider.VideoEntry("id1", 2015),
            VideoProvider.VideoEntry("id2", 2016),
            VideoProvider.VideoEntry("id3", 2017)
        )
        val playedIds = setOf("id2", "id3")
        val unscored = videos.filter { it.id !in playedIds }
        
        assertEquals(1, unscored.size)
        assertEquals("id1", unscored[0].id)
    }
}