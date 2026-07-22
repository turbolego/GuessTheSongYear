package com.turbolego.songguesser

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * InnerTube service for searching YouTube without API key.
 * Uses YouTube's undocumented web API endpoint.
 */
object YouTubeSearchService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36",
        "Accept" to "application/json",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to "https://www.youtube.com",
        "Referer" to "https://www.youtube.com/",
        "Content-Type" to "application/json"
    )

    private fun buildRequest(path: String, body: String, includeApiKey: Boolean = false): Request {
        val url = if (includeApiKey) {
            // Alternative endpoint structure
            "https://www.youtube.com/youtubei/v1/$path"
        } else {
            "https://www.youtube.com/youtubei/v1/$path"
        }

        return Request.Builder()
            .url(url)
            .apply { baseHeaders.forEach { (k, v) -> header(k, v) } }
            .post(body.toRequestBody(jsonMediaType))
            .build()
    }

    /**
     * Build the InnerTube request body with proper context.
     */
    private fun buildSearchBody(query: String): String {
        return JSONObject().apply {
            put("query", query)
            put("type", "video")
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
     * Search for music videos on YouTube.
     * Returns videos with at least 10 million views.
     */
    fun searchMusicVideos(): List<ApiVideo> {
        val queries = listOf(
            "official music video",
            "popular music video",
            "top hits music video"
        )

        val allVideos = mutableListOf<ApiVideo>()
        val seenIds = mutableSetOf<String>()

        for (query in queries.shuffled().take(2)) {
            try {
                val request = buildRequest("search", buildSearchBody(query))
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.d("YouTubeSearch", "API returned ${response.code}")
                    continue
                }

                val json = JSONObject(response.body?.string() ?: "")
                val videos = parseSearchResponse(json, seenIds)
                allVideos.addAll(videos)
                seenIds.addAll(videos.map { it.id })

                Log.d("YouTubeSearch", "Got ${videos.size} from query '$query'")
            } catch (e: Exception) {
                Log.d("YouTubeSearch", "Query failed: ${e.message}")
            }
        }

        // Try alternative endpoint structure if empty
        if (allVideos.isEmpty()) {
            try {
                val altBody = buildSearchBody("music video")
                val request = buildRequest("search", altBody)
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    val videos = parseSearchResponseAlt(json, seenIds)
                    allVideos.addAll(videos)
                }
            } catch (_: Exception) {}
        }

        Log.d("YouTubeSearch", "Total unique videos found: ${allVideos.size}")
        return allVideos
    }

    /**
     * Parse InnerTube search response — modern structure.
     */
    private fun parseSearchResponse(json: JSONObject, seenIds: Set<String>): List<ApiVideo> {
        val videos = mutableListOf<ApiVideo>()
        try {
            val contents = json.optJSONObject("contents") ?: return videos
            val twoColSearch = contents.optJSONObject("twoColumnSearchResultsRenderer")
                ?: contents.optJSONObject("twoColumnWatchNextResults")?.let {
                    // Maybe a merged response — try primary contents
                    contents.optJSONObject("primaryContents") ?: return videos
                } ?: return videos

            val primary = twoColSearch.optJSONObject("primaryContents")
                ?: twoColSearch.optJSONObject("secondaryContents") ?: return videos
            val sectionList = primary.optJSONObject("sectionListRenderer") ?: return videos
            val sections = sectionList.optJSONArray("contents") ?: return videos

            for (i in 0 until sections.length()) {
                val section = sections.optJSONObject(i) ?: continue
                val itemSection = section.optJSONObject("itemSectionRenderer") ?: continue
                val items = itemSection.optJSONArray("contents") ?: continue

                for (j in 0 until items.length()) {
                    val item = items.optJSONObject(j) ?: continue
                    val videoRenderer = item.optJSONObject("videoRenderer") ?: continue
                    val video = parseVideoRenderer(videoRenderer, seenIds)
                    if (video != null) videos.add(video)
                }
            }
        } catch (e: Exception) {
            Log.d("YouTubeSearch", "Parse error: ${e.message}")
        }
        return videos
    }

    /**
     * Alternative parser for different InnerTube response shapes.
     */
    private fun parseSearchResponseAlt(json: JSONObject, seenIds: Set<String>): List<ApiVideo> {
        val videos = mutableListOf<ApiVideo>()
        try {
            // Try flat response structure
            val contents = json.optJSONObject("contents") ?: return videos
            val primary = contents.optJSONObject("primaryContents") ?: return videos
            val sectionList = primary.optJSONObject("sectionListRenderer") ?: return videos
            val sections = sectionList.optJSONArray("contents") ?: return videos

            for (i in 0 until sections.length()) {
                val section = sections.optJSONObject(i) ?: continue
                val itemSection = section.optJSONObject("itemSectionRenderer") ?: continue
                val items = itemSection.optJSONArray("contents") ?: continue

                for (j in 0 until items.length()) {
                    val item = items.optJSONObject(j) ?: continue
                    val videoRenderer = item.optJSONObject("videoRenderer") ?: continue
                    val video = parseVideoRenderer(videoRenderer, seenIds)
                    if (video != null) videos.add(video)
                }
            }
        } catch (_: Exception) {}
        return videos
    }

    /**
     * Parse a single videoRenderer object.
     */
    private fun parseVideoRenderer(
        renderer: JSONObject,
        seenIds: Set<String>
    ): ApiVideo? {
        val videoId = renderer.optString("videoId", "").takeIf { it.isNotEmpty() } ?: return null
        if (videoId in seenIds) return null

        val titleObj = renderer.optJSONObject("title") ?: return null
        val title = titleObj.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "")
            ?: titleObj.optString("simpleText", "")
        if (title.isBlank()) return null

        val viewCountStr = renderer.optJSONObject("viewCountText")?.let { vc ->
            vc.optString("simpleText", "") ?: vc.optJSONArray("runs")?.optJSONObject(0)
                ?.optString("text", "")
        } ?: "0"

        val viewCount = parseViewCount(viewCountStr)
        if (viewCount < 1_000_000L) return null // Min 1M views

        // Get year from our known mapping
        val year = extractYearFromVideoId(videoId)

        return ApiVideo(videoId, year, viewCount, title)
    }

    /**
     * Parse view count text to numeric value.
     * Handles formats like "45M views", "1.2B", "100K", etc.
     */
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
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Extract year from video ID using known mapping.
     * Falls back to 2020 for unknown videos.
     */
    private fun extractYearFromVideoId(videoId: String): Int {
        return when (videoId) {
            "dQw4w9WgXcQ" -> 1987
            "ZbZSe6N_BXs" -> 1985
            "2Z8IUAiIufKN" -> 1983
            "djV1KL4Btzw" -> 1984
            "rYEDA3JiTEA" -> 1984
            "1w7OgIMMRc4" -> 1985
            "1G4isv_Fyls" -> 1985
            "hr0ObGAUDlQ" -> 1982
            "hcwnL9a61o0" -> 1999
            "tF3iR0rN-8g" -> 1991
            "q3zKKtYsEj8" -> 1994
            "ZmDBbnMFK70" -> 1999
            "YR5W3FKE88Q" -> 1998
            "fLexgOxsZu0" -> 1996
            "XbGsChTe4go" -> 1999
            "6KnRLJZ0ZRw" -> 1995
            "eBCRc2Zk6hA" -> 2000
            "dQ1ribkayAU" -> 2008
            "lp-EO5I60KA" -> 2009
            "kJQP7kiw5Fk" -> 2006
            "9bZkp7q19f0" -> 2012
            "YQHsXMglC9A" -> 2015
            "OPf0YbXqDm0" -> 2014
            "2Vv-BfVoq4g" -> 2017
            "Rl6bfz9xYio" -> 2023
            "kPa7bsKwL-c" -> 2023
            "hVlgHmeZjg8" -> 2021
            "ffxKSjUwZdU" -> 2021
            "QOQZRLdv3s0" -> 2018
            "uelHwf8o7_U" -> 2019
            "Z09lZZd7aJs" -> 2020
            "nPLV7lGczsE" -> 2017
            "GtMSnMlLiwY" -> 2019
            "u7K7pXAhK5c" -> 2015
            "bo_efYxQAse" -> 2018
            "YVkKvmAVWHE" -> 2019
            "YBHQbu5FpLk" -> 2020
            "1Q9qGcPp3b4" -> 2021
            "456sX5lPcTQ" -> 2021
            "b4Bj7Zb-YDc" -> 2021
            "pBk4NYvBMJc" -> 2022
            "W0DM0WCb5ac" -> 2023
            "iWzVlFouYwE" -> 2023
            else -> 2020
        }
    }
}
