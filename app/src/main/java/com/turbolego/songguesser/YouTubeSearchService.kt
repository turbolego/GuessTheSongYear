package com.turbolego.songguesser

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * InnerTube service for searching YouTube videos by year — exact port of
 * the Spotify version's approach (search by year, pick random result).
 *
 * Uses YouTube's undocumented web API endpoint (InnerTube).
 * No API key required.
 */
object YouTubeSearchService {

    private const val TAG = "YouTubeSearch"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36",
        "Accept" to "application/json",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to "https://www.youtube.com",
        "Referer" to "https://www.youtube.com/",
        "Content-Type" to "application/json"
    )

    /**
     * Build the InnerTube request body with a query that targets a specific year.
     * Uses the same pattern as the Spotify version: search for music from that year.
     */
    private fun buildSearchBody(year: Int): String {
        val query = "popular songs $year music video"
        return JSONObject().apply {
            put("query", query)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB")
                    put("clientVersion", "2.20250301.07.00")
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            put("params", "EgIQAQ%3D%3D") // Search type: video
        }.toString()
    }

    /**
     * Search YouTube for music videos from [year].
     * Returns up to [maxResults] videos, each with the given [year] assigned.
     * Returns empty list on any failure (caller should fall back to hardcoded list).
     */
    fun searchByYear(year: Int, maxResults: Int = 20): List<ApiVideo> {
        try {
            val body = buildSearchBody(year)
            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/search")
                .apply { baseHeaders.forEach { (k, v) -> header(k, v) } }
                .post(body.toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "API returned ${response.code} for year $year")
                return emptyList()
            }

            val json = JSONObject(response.body?.string() ?: "")
            val videos = parseSearchResponse(json, year)
            Log.d(TAG, "Found ${videos.size} videos for year $year")
            return videos.take(maxResults)

        } catch (e: Exception) {
            Log.w(TAG, "Search failed for year $year: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Fetch videos for multiple years. Picks [yearCount] distinct random years
     * using [YearRandomizer.prioritizeModernYears], fetches up to [perYear]
     * videos per year, then deduplicates and returns the combined list.
     */
    fun searchMultipleYears(yearCount: Int = 3, perYear: Int = 10): List<ApiVideo> {
        val seenIds = mutableSetOf<String>()
        val allVideos = mutableListOf<ApiVideo>()

        val years = (1..yearCount).map { YearRandomizer.prioritizeModernYears() }

        for (year in years.distinct()) {
            val videos = searchByYear(year, perYear)
            for (v in videos) {
                if (v.id !in seenIds) {
                    seenIds.add(v.id)
                    allVideos.add(v)
                }
            }
        }

        Log.d(TAG, "Total unique videos from API: ${allVideos.size}")
        return allVideos
    }

    // ── InnerTube JSON parsing ─────────────────────────────────────────────

    private fun parseSearchResponse(json: JSONObject, year: Int): List<ApiVideo> {
        val videos = mutableListOf<ApiVideo>()
        try {
            val contents = json.optJSONObject("contents") ?: return videos
            val twoColSearch = contents.optJSONObject("twoColumnSearchResultsRenderer")
                ?: contents.optJSONObject("twoColumnWatchNextResults")
                    ?.let { contents.optJSONObject("primaryContents") }
                ?: return videos

            val primary = twoColSearch.optJSONObject("primaryContents")
                ?: twoColSearch.optJSONObject("secondaryContents")
                ?: return videos

            val sectionList = primary.optJSONObject("sectionListRenderer") ?: return videos
            val sections = sectionList.optJSONArray("contents") ?: return videos

            for (i in 0 until sections.length()) {
                val section = sections.optJSONObject(i) ?: continue
                val itemSection = section.optJSONObject("itemSectionRenderer") ?: continue
                val items = itemSection.optJSONArray("contents") ?: continue

                for (j in 0 until items.length()) {
                    val item = items.optJSONObject(j) ?: continue
                    val renderer = item.optJSONObject("videoRenderer") ?: continue
                    val video = parseVideoRenderer(renderer, year)
                    if (video != null) videos.add(video)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Parse error: ${e.message}")
        }
        return videos
    }

    private fun parseVideoRenderer(renderer: JSONObject, year: Int): ApiVideo? {
        val videoId = renderer.optString("videoId", "").takeIf { it.isNotEmpty() } ?: return null

        val titleObj = renderer.optJSONObject("title") ?: return null
        val title = titleObj.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text", "")
            ?: titleObj.optString("simpleText", "")
        if (title.isBlank()) return null

        val viewCountStr = renderer.optJSONObject("viewCountText")?.let { vc ->
            vc.optString("simpleText", "")
                ?: vc.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "")
        } ?: "0"

        val viewCount = parseViewCount(viewCountStr)

        // Filter out shorts / unreasonably low-view videos
        if (viewCount < 500_000L) return null

        return ApiVideo(videoId, year, viewCount, title)
    }

    private fun parseViewCount(text: String): Long {
        return try {
            val cleaned = text.replace(Regex("[^0-9.]"), "")
            val multiplier = when {
                text.contains("B", true) -> 1_000_000_000L
                text.contains("M", true) -> 1_000_000L
                text.contains("K", true) -> 1_000L
                else -> 1L
            }
            val num = cleaned.toDoubleOrNull() ?: 0.0
            (num * multiplier).toLong()
        } catch (_: Exception) { 0L }
    }
}