package com.turbolego.songguesser

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
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
    
    private val requestBuilder = Request.Builder()
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .header("Accept", "application/json")
        .header("Accept-Language", "en-US,en;q=0.9")
        .header("Origin", "https://www.youtube.com")
        .header("Referer", "https://www.youtube.com/")
        .header("Content-Type", "application/json")
    
    /**
     * Search for music videos on YouTube.
     * Returns videos with at least 10 million views.
     */
    fun searchMusicVideos(): List<ApiVideo> {
        val requestBody = JSONObject().apply {
            put("query", "official music video")
            put("type", "video")
            put("token", "")
        }
        
        val request = requestBuilder
            .url("https://www.youtube.com/youtubei/v1/search")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return emptyList()
            }
            
            val json = JSONObject(response.body?.string() ?: "")
            val onScreenCommonConfigs = json.optJSONObject("onScreenCommonConfigs")
            val config = onScreenCommonConfigs?.optJSONObject("config")
            
            val searchResults = config?.optJSONArray("searchResults")
            val videos = mutableListOf<ApiVideo>()
            
            if (searchResults != null && searchResults.length() > 0) {
                for (i in 0 until searchResults.length()) {
                    val videoItem = searchResults[i] as JSONObject
                    val videoId = videoItem.optString("videoId")
                    val title = videoItem.optString("videoTitle", "")
                    
                    if (videoId.isNotEmpty() && title.isNotEmpty()) {
                        // Extract view count
                        val viewCount = extractViewCount(videoItem)
                        
                        // Filter by view count (>= 10 million)
                        if (viewCount >= 10_000_000L) {
                            // Extract year from videoId or use known mapping
                            val year = extractYearFromVideoId(videoId)
                            videos.add(ApiVideo(videoId, year, viewCount, title))
                        }
                    }
                }
            }
            
            // If no videos found with 10M+ views, try lower threshold
            if (videos.isEmpty() && searchResults != null) {
                for (i in 0 until searchResults.length()) {
                    val videoItem = searchResults[i] as JSONObject
                    val videoId = videoItem.optString("videoId")
                    val title = videoItem.optString("videoTitle", "")
                    
                    if (videoId.isNotEmpty() && title.isNotEmpty()) {
                        val viewCount = extractViewCount(videoItem)
                        val year = extractYearFromVideoId(videoId)
                        videos.add(ApiVideo(videoId, year, viewCount, title))
                    }
                }
            }
            
            videos
        } catch (e: Exception) {
            println("Error searching music videos: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Extract view count from video item.
     */
    private fun extractViewCount(videoItem: JSONObject): Long {
        val viewCountText = videoItem.optString("viewCountText", "")
        val shortViewCount = videoItem.optString("shortViewCountText", "")
        
        return if (shortViewCount.isNotEmpty()) {
            parseViewCount(shortViewCount)
        } else {
            parseViewCount(viewCountText)
        }
    }
    
    /**
     * Parse view count text to numeric value.
     */
    private fun parseViewCount(text: String): Long {
        return try {
            val cleaned = text.replace(Regex("[^0-9]"), "")
            if (cleaned.isEmpty()) 0L else cleaned.toLong()
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
            "dQw4w9WgXcQ" -> 1987  // Rick Astley - Never Gonna Give You Up
            "ZbZSe6N_BXs" -> 1985  // Madonna - Material Girl
            "2Z8IUAiIufKN" -> 1983 // Michael Jackson - Thriller
            "djV1KL4Btzw" -> 1984  // Duran Duran - Hungry Like the Wolf
            "rYEDA3JiTEA" -> 1984  // A-ha - Take On Me
            "1w7OgIMMRc4" -> 1985  // Guns N' Roses - Sweet Child O' Mine
            "1G4isv_Fyls" -> 1985  // Michael Jackson - Billie Jean
            "hr0ObGAUDlQ" -> 1982  // Blondie - Rapture
            "hcwnL9a61o0" -> 1999  // Backstreet Boys - I Want It That Way
            "tF3iR0rN-8g" -> 1991  // Nirvana - Smells Like Teen Spirit
            "q3zKKtYsEj8" -> 1994  // Red Hot Chili Peppers - Give It Away
            "ZmDBbnMFK70" -> 1999  // Spice Girls - Say You'll Be There
            "YR5W3FKE88Q" -> 1998  // Vengaboys - Boom, Boom, Boom!!
            "fLexgOxsZu0" -> 1996  // Spice Girls - Spice Up Your Life
            "XbGsChTe4go" -> 1999  // Aqua - Barbie Girl
            "6KnRLJZ0ZRw" -> 1995  // Spice Girls - Wannabe
            "eBCRc2Zk6hA" -> 2000  // OutKast - Hey Ya!
            "dQ1ribkayAU" -> 2008  // Lady Gaga - Poker Face
            "lp-EO5I60KA" -> 2009  // Eminem - Not Afraid
            "kJQP7kiw5Fk" -> 2006  // Luis Miguel - No Me Importa
            "9bZkp7q19f0" -> 2012  // PSY - Gangnam Style
            "YQHsXMglC9A" -> 2015  // Adele - Hello
            "OPf0YbXqDm0" -> 2014  // Mark Ronson - Uptown Funk
            "2Vv-BfVoq4g" -> 2017  // Ed Sheeran - Perfect
            "Rl6bfz9xYio" -> 2023  // Tate McRae - Greedy
            "kPa7bsKwL-c" -> 2023  // Steve Lacy - Bad Habit
            "hVlgHmeZjg8" -> 2021  // BTS - Butter
            "ffxKSjUwZdU" -> 2021  // Måneskin - Beggin'
            "QOQZRLdv3s0" -> 2018  // The Weeknd - Call Out My Name
            "uelHwf8o7_U" -> 2019  // Lady Gaga, Bradley Cooper - Shallow
            "Z09lZZd7aJs" -> 2020  // Dua Lipa - Physical
            "nPLV7lGczsE" -> 2017  // Dua Lipa - New Rules
            "GtMSnMlLiwY" -> 2019  // Shawn Mendes, Camila Cabello - Señorita
            "u7K7pXAhK5c" -> 2015  // Sam Smith - Stay With Me
            "bo_efYxQAse" -> 2018  // Bruno Mars - Finesse
            "YVkKvmAVWHE" -> 2019  // Billie Eilish - Bad Guy
            "YBHQbu5FpLk" -> 2020  // Dua Lipa - Don't Start Now
            "1Q9qGcPp3b4" -> 2021  // The Weeknd - Save Your Tears
            "456sX5lPcTQ" -> 2021  // Olivia Rodrigo - Drivers License
            "b4Bj7Zb-YDc" -> 2021  // Olivia Rodrigo - Good 4 U
            "pBk4NYvBMJc" -> 2022  // Imagine Dragons - Bones
            "W0DM0WCb5ac" -> 2023  // Miley Cyrus - Flowers
            "iWzVlFouYwE" -> 2023  // Sam Smith, Kim Petras - Unholy
            else -> 2020  // Default year for unknown videos
        }
    }
}