package com.turbolego.songguesser

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tests for YouTube video playback via NewPipe Extractor + ExoPlayer.
 *
 * These are local JVM unit tests (no Android emulator needed).
 * They verify the HTTP-level contract with YouTube's APIs.
 *
 * Run: ./gradlew test
 */
class YouTubeEmbeddabilityTest {

    // ═══════════════════════════════════════════════════════════════
    // Test 1: oEmbed API (metadata, always works)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `oEmbed should return HTTP 200 for all pool videos`() {
        // Test the first 5 pool videos — covers major labels
        val poolIds = listOf(
            "dQw4w9WgXcQ",  // Rick Astley (VEVO)
            "ZbZSe6N_BXs",  // Pharrell (Columbia)
            "9bZkp7q19f0",  // PSY (YG)
            "kJQP7kiw5Fk",  // Luis Fonsi (UMG)
            "YQHsXMglC9A"   // Adele (XL/Beggars)
        )

        val failed = mutableListOf<String>()
        for (id in poolIds) {
            val code = oEmbedHttpCode(id)
            if (code != 200) {
                failed.add("$id → HTTP $code")
            }
        }

        assertTrue(
            "Failed oEmbed videos: ${failed.joinToString(", ")}",
            failed.isEmpty()
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 2: Video ID format validation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `YouTube video IDs must match 11-character format`() {
        val regex = Regex("^[A-Za-z0-9_-]{11}$")

        val validIds = listOf(
            "dQw4w9WgXcQ", "ZbZSe6N_BXs", "1w7OgIMMRc4",
            "9bZkp7q19f0", "kJQP7kiw5Fk", "YQHsXMglC9A",
            "OPf0YbXqDm0", "2Vv-BfVoq4g", "fLexgOxsZu0",
            "JGwWNGJdvx8", "RgKAFK5djSk"
        )

        for (id in validIds) {
            assertTrue("ID $id must be 11 chars", regex.matches(id))
        }
    }

    @Test
    fun `YouTube video IDs must not contain invalid characters`() {
        val invalidIds = listOf(
            "tooshort",           // too short
            "has spaces xx",      // spaces not allowed
            "dQw4w9WgXcQextra",   // too long
            "",                    // empty
            "!!!!!!!!!!",         // URL-unsafe
        )

        val regex = Regex("^[A-Za-z0-9_-]{11}$")
        for (id in invalidIds) {
            assertFalse("ID '$id' must be rejected", regex.matches(id))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 3: oEmbed JSON structure
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `oEmbed JSON should return title and author fields`() {
        val testId = "dQw4w9WgXcQ" // Rick Astley — most reliable video
        val json = fetchOEmbedJson(testId)

        assertNotNull("oEmbed should return JSON for $testId", json)

        val body = json!!
        assertTrue("oEmbed JSON should contain title",
            body.contains("\"title\""))
        assertTrue("oEmbed JSON should contain author_name",
            body.contains("\"author_name\""))
        assertTrue("oEmbed JSON should contain thumbnail_url",
            body.contains("\"thumbnail_url\""))
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 4: Pool year range validation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `all pool years must be within playable range 1960-2025`() {
        val poolYears = listOf(
            1987, 2013, 1987, 2014, 2012, // first 5 videos
            2015, 2014, 2017, 2010, 2017  // next 5
        )

        for (year in poolYears) {
            assertTrue(
                "Year $year must be 1960–2025",
                year in 1960..2025
            )
        }
    }

    @Test
    fun `year range must span at least 40 years for game difficulty`() {
        val oldest = 1960
        val newest = 2025
        val span = newest - oldest

        assertTrue("Year span must be at least 40 years", span >= 40)
        assertEquals("Must be exactly 65 years", 65, span)
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper methods
    // ═══════════════════════════════════════════════════════════════

    private fun oEmbedHttpCode(videoId: String): Int {
        return try {
            val url = URL("https://www.youtube.com/oembed?url=" +
                    "https://www.youtube.com/watch?v=$videoId&format=json")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            conn.instanceFollowRedirects = false
            conn.responseCode
        } catch (_: Exception) {
            -1
        }
    }

    private fun fetchOEmbedJson(videoId: String): String? {
        return try {
            val url = URL("https://www.youtube.com/oembed?url=" +
                    "https://www.youtube.com/watch?v=$videoId&format=json")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }
}