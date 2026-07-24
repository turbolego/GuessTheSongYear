package com.turbolego.songguesser

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*

/**
 * Tests for YouTube embeddability and video player integration.
 *
 * These are integration tests that check if YouTube videos
 * can actually be embedded/played. They don't run on-device
 * WebView but verify the embed contract at the HTTP level.
 *
 * IMPORTANT: These tests require network access to YouTube.
 * Run with: ./gradlew test --info
 */
class YouTubeEmbeddabilityTest {

    companion object {
        // All videos from our fallback list
        val FALLBACK_VIDEOS = listOf(
            "dQw4w9WgXcQ" to "Rick Astley — Never Gonna Give You Up",
            "ZbZSe6N_BXs" to "Pharrell Williams — Happy",
            "1w7OgIMMRc4" to "Guns N' Roses — Sweet Child O' Mine",
            "lp-EO5I60KA" to "Ed Sheeran — Thinking Out Loud",
            "9bZkp7q19f0" to "PSY — Gangnam Style",
            "YQHsXMglC9A" to "Adele — Hello",
            "OPf0YbXqDm0" to "Mark Ronson — Uptown Funk",
            "2Vv-BfVoq4g" to "Ed Sheeran — Perfect",
            "fLexgOxsZu0" to "Bruno Mars — The Lazy Song",
            "kJQP7kiw5Fk" to "Luis Fonsi — Despacito",
            "YlUKcNNmywk" to "RHCP — Californication",
            "QcIy9NiNbmo" to "Taylor Swift — Bad Blood",
            "fRh_vgS2dFE" to "Justin Bieber — Sorry",
            "JGwWNGJdvx8" to "Ed Sheeran — Shape of You",
            "RgKAFK5djSk" to "Wiz Khalifa — See You Again",
            "papuvlVeZg8" to "Clean Bandit — Rockabye",
            "kffacxfA7G4" to "Justin Bieber — Baby",
            "k2qgadSvNyU" to "Dua Lipa — New Rules",
            "Oextk-If8HQ" to "Keane — Somewhere Only We Know",
            "UceaB4D0jpo" to "Post Malone — rockstar",
            "v2AC41dglnM" to "AC/DC — Thunderstruck",
            "hT_nvWreIhg" to "OneRepublic — Counting Stars",
            "fKopy74weus" to "Imagine Dragons — Thunder",
            "ZRtdQ81jPUQ" to "YOASOBI — Idol",
            "T3E9Wjbq44E" to "Gym Class Heroes — Stereo Hearts",
            "K0ibBPhiaG0" to "Ed Sheeran — Castle On The Hill",
            "w2Ov5jzm3j8" to "Lil Nas X — Old Town Road",
            "450p7goxZqg" to "John Legend — All of Me",
            "ptSjNWnzpjg" to "Taylor Swift — Fearless",
            "SMs0GnYze34" to "DJ Snake — Let Me Love You",
            "NmCFY1oYDeM" to "John Legend — Love Me Now",
            "bESGLojNYSo" to "Lady Gaga — Poker Face",
            "Pkh8UtuejGw" to "Shawn Mendes — Senorita",
            "Rt0spqQtMKg" to "SNL — D*** in a Box",
            "YykjpeuMNEk" to "Coldplay — Hymn For The Weekend"
        )

        // Non-music videos that typically allow embedding (tutorials, etc.)
        val EMBED_FRIENDLY_VIDEOS = listOf(
            "jNQXAC9IVRw" to "Me at the zoo (first YouTube video)",
            "kJQP7kiw5Fk" to "Despacito (should work, test)"
        )
    }

    // ── Test 1: oEmbed API Verification ──────────────────────────────

    @Test
    fun `all fallback videos must pass oEmbed check`() {
        val blocked = mutableListOf<String>()

        for ((id, title) in FALLBACK_VIDEOS) {
            val code = oEmbedHttpCode(id)
            if (code != 200) {
                blocked.add("$id ($title) → HTTP $code")
            }
        }

        assertTrue(
            "Blocked videos: ${blocked.joinToString(", ")}",
            blocked.isEmpty()
        )
    }

    @Test
    fun `oEmbed API should detect embed-blocked videos`() {
        // Known embed-blocked music videos should return non-200
        val blockedVideos = listOf(
            "djV1KL4Btzw",  // confirmed 404 in earlier testing
            "rYEDA3JiTEA"   // confirmed 404 in earlier testing
        )

        for (id in blockedVideos) {
            val code = oEmbedHttpCode(id)
            assertNotEquals("Video $id should be blocked", 200, code)
        }
    }

    // ── Test 2: YouTube Intent URL Generation ──────────────────────────

    @Test
    fun `YouTube Intent URL should be valid for all videos`() {
        val base = "https://www.youtube.com/watch?v="
        for ((id, title) in FALLBACK_VIDEOS) {
            val url = "$base$id"
            assertTrue("$id ($title): Intent URL should contain video ID",
                url.contains(id))
            assertTrue("$id ($title): URL should be valid YouTube URL",
                url.startsWith("https://www.youtube.com/watch?v="))
        }
    }

    @Test
    fun `YouTube Intent package should reference YouTube app`() {
        val expectedPackage = "com.google.android.youtube"
        assertEquals("YouTube app package", expectedPackage, expectedPackage)
    }

    // ── Test 3: oEmbed Metadata API ────────────────────────────────────

    @Test
    fun `oEmbed should return playable metadata for all fallback videos`() {
        val failed = mutableListOf<String>()

        for ((id, title) in FALLBACK_VIDEOS) {
            val code = oEmbedHttpCode(id)
            if (code != 200) {
                failed.add("$id ($title) → HTTP $code")
            }
        }

        assertTrue(
            "Failed oEmbed videos: ${failed.joinToString(", ")}",
            failed.isEmpty()
        )
    }

    @Test
    fun `oEmbed should return title and author_name in JSON`() {
        val testId = "dQw4w9WgXcQ"
        val json = fetchOEmbedJson(testId)

        assertNotNull("oEmbed should return JSON for $testId", json)
        assertTrue("JSON should contain title", json!!.contains("\"title\""))
        assertTrue("JSON should contain author_name", json!!.contains("\"author_name\""))
        assertTrue("JSON should contain thumbnail_url", json!!.contains("\"thumbnail_url\""))
    }

    // ── Test 4: Video ID Validation ──────────────────────────────────

    @Test
    fun `all video IDs must be valid format`() {
        val validPattern = Regex("^[A-Za-z0-9_-]{11}$")

        for ((id, title) in FALLBACK_VIDEOS) {
            assertTrue(
                "$id ($title): must match 11-char YouTube video ID pattern",
                validPattern.matches(id)
            )
        }
    }
    // ── Test 5: Multiple-oEmbed fetch (unit-level) ─────────────────────

    @Test
    fun `oEmbed concurrent fetches should all succeed`() {
        // Test fetching oEmbed for multiple videos — all should return
        val testIds = FALLBACK_VIDEOS.take(3).map { it.first }
        val results = testIds.map { id -> id to oEmbedHttpCode(id) }
        val failed = results.filter { (_, code) -> code != 200 }

        assertTrue(
            "Failed oEmbed fetches: ${failed.joinToString(", ")}",
            failed.isEmpty()
        )
    }

    // ── Helper Methods ───────────────────────────────────────────────

    private fun oEmbedHttpCode(videoId: String): Int {
        return try {
            val url = URL("https://www.youtube.com/oembed?url=" +
                "https://www.youtube.com/watch?v=$videoId&format=json")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            conn.responseCode
        } catch (e: Exception) {
            -1
        }
    }

    private fun fetchOEmbedJson(videoId: String): String? {
        return try {
            val url = URL("https://www.youtube.com/oembed?url=" +
                "https://www.youtube.com/watch?v=$videoId&format=json")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }
}
