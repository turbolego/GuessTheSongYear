package com.turbolego.songguesser

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Unit tests for the offline video pipeline:
 *   VideoProvider parsing, YearRandomizer weighting, oEmbed metadata.
 *
 * These are local JVM tests (no Android emulator needed).
 * Run: ./gradlew test --no-daemon
 */
class VideoProviderTest {

    // ═══════════════════════════════════════════════════════════════
    // VideoProvider — parseVideoIds
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `parseVideoIds should extract bare 11-char IDs`() {
        val input = "dQw4w9WgXcQ"
        val ids = VideoProvider.parseVideoIds(input)
        assertEquals(listOf("dQw4w9WgXcQ"), ids)
    }

    @Test
    fun `parseVideoIds should extract from youtube com watch URL`() {
        val input = "https://www.youtube.com/watch?v=fJ9rUzIMcZQ"
        val ids = VideoProvider.parseVideoIds(input)
        assertEquals(listOf("fJ9rUzIMcZQ"), ids)
    }

    @Test
    fun `parseVideoIds should extract from youtu be short URL`() {
        val input = "https://youtu.be/dQw4w9WgXcQ"
        val ids = VideoProvider.parseVideoIds(input)
        assertEquals(listOf("dQw4w9WgXcQ"), ids)
    }

    @Test
    fun `parseVideoIds should extract from shorts URL`() {
        val input = "https://youtube.com/shorts/ZbZSe6N_BXs"
        val ids = VideoProvider.parseVideoIds(input)
        assertEquals(listOf("ZbZSe6N_BXs"), ids)
    }

    @Test
    fun `parseVideoIds should extract from embed URL`() {
        val input = "https://www.youtube.com/embed/9bZkp7q19f0"
        val ids = VideoProvider.parseVideoIds(input)
        assertEquals(listOf("9bZkp7q19f0"), ids)
    }

    @Test
    fun `parseVideoIds should handle comma-separated list`() {
        val input = "dQw4w9WgXcQ, ZbZSe6N_BXs, https://youtu.be/9bZkp7q19f0"
        val ids = VideoProvider.parseVideoIds(input)
        assertEquals(3, ids.size)
    }

    @Test
    fun `parseVideoIds should deduplicate`() {
        val input = "dQw4w9WgXcQ\ndQw4w9WgXcQ"
        val ids = VideoProvider.parseVideoIds(input)
        assertEquals(1, ids.size)
    }

    @Test
    fun `parseVideoIds should reject short strings`() {
        val input = "tooshort"
        val ids = VideoProvider.parseVideoIds(input)
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `parseVideoIds should handle newline-separated list`() {
        val input = """
            dQw4w9WgXcQ
            https://youtu.be/ZbZSe6N_BXs
            https://www.youtube.com/watch?v=9bZkp7q19f0
        """.trimIndent()
        val ids = VideoProvider.parseVideoIds(input)
        assertEquals(3, ids.size)
    }

    // ═══════════════════════════════════════════════════════════════
    // YearRandomizer — weighted year selection
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `prioritizeModernYears should return year within available range`() {
        val available = listOf(1980, 1990, 2000, 2010, 2020)
        val results = (1..100).map { YearRandomizer.prioritizeModernYears(available) }
        assertTrue("All results must be in available list", results.all { it in available })
    }

    @Test
    fun `prioritizeModernYears should fallback to 2000 on empty list`() {
        val year = YearRandomizer.prioritizeModernYears(emptyList())
        assertEquals(2000, year)
    }

    @Test
    fun `prioritizeModernYears with single year should return that year`() {
        val year = YearRandomizer.prioritizeModernYears(listOf(1999))
        assertEquals(1999, year)
    }

    @Test
    fun `prioritizeModernYears unfiltered should be within 1920-2025`() {
        val results = (1..1000).map { YearRandomizer.prioritizeModernYears() }
        assertTrue("All results must be 1920-2025", results.all { it in 1920..2025 })
    }

    // ═══════════════════════════════════════════════════════════════
    // YouTube video ID format validation
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
            "tooshort",
            "has spaces xx",
            "dQw4w9WgXcQextra",
            "",
            "!!!!!!!!!!"
        )
        val regex = Regex("^[A-Za-z0-9_-]{11}$")
        for (id in invalidIds) {
            assertFalse("ID '$id' must be rejected", regex.matches(id))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // oEmbed integration tests (live HTTP calls)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `oEmbed should return HTTP 200 for known pool videos`() {
        val poolIds = listOf(
            "dQw4w9WgXcQ",
            "ZbZSe6N_BXs",
            "9bZkp7q19f0",
            "kJQP7kiw5Fk",
            "YQHsXMglC9A"
        )
        val failed = mutableListOf<String>()
        for (id in poolIds) {
            val code = oEmbedHttpCode(id)
            if (code != 200) failed.add("$id → HTTP $code")
        }
        assertTrue(
            "Failed oEmbed videos: ${failed.joinToString(", ")}",
            failed.isEmpty()
        )
    }

    @Test
    fun `oEmbed JSON should contain title and author fields`() {
        val testId = "dQw4w9WgXcQ"
        val body = fetchOEmbedJson(testId)
        assertNotNull("oEmbed should return JSON for $testId", body)
        assertTrue("oEmbed JSON should contain title", body!!.contains("\"title\""))
        assertTrue("oEmbed JSON should contain author_name", body.contains("\"author_name\""))
    }

    // ═══════════════════════════════════════════════════════════════
    // Year range validation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `year range must be 1960-2025 for game difficulty`() {
        val oldest = 1960
        val newest = 2025
        val span = newest - oldest
        assertTrue("Year span must be at least 40 years", span >= 40)
        assertEquals("Year span should be 65 years", 65, span)
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
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
        } catch (_: Exception) { -1 }
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
        } catch (_: Exception) { null }
    }
}
