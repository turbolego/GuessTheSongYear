package com.example.guessthesongyear.util

import android.content.Context
import android.util.Log
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

private const val TAG = "YouTubeApiService"

/**
 * Handles interactions with the YouTube Data API
 */
class YouTubeApiService(private val context: Context) {

    private val transport = NetHttpTransport()
    private val jsonFactory = JacksonFactory.getDefaultInstance()
    private val applicationName = "GuessTheSongYear"
    
    // Data class to hold video information
    data class VideoInfo(
        val id: String,
        val releaseYear: Int? = null
    )

    /**
     * Gets a random music video with at least 1 million views
     */
    suspend fun getRandomMusicVideo(): VideoInfo? = withContext(Dispatchers.IO) {
        try {
            val youtubeService = getYouTubeService() ?: return@withContext null

            // Query for popular music videos
            val searchRequest = youtubeService.search().list(listOf("id"))
            searchRequest.part = listOf("id")
            searchRequest.type = listOf("video")
            searchRequest.videoCategoryId = "10" // Music category
            searchRequest.videoDefinition = "high"
            searchRequest.maxResults = 50L
            
            // Use one of these popular search parameters randomly
            val popularQueries = listOf(
                "top hits music video",
                "popular music video",
                "official music video",
                "top 40 music video",
                "viral music video"
            )
            searchRequest.q = popularQueries[Random.nextInt(popularQueries.size)]
            
            val searchResponse = searchRequest.execute()
            
            // Get a random video from the results
            if (searchResponse.items.isNotEmpty()) {
                // Select a random video
                val randomIndex = Random.nextInt(searchResponse.items.size)
                val videoId = searchResponse.items[randomIndex].id.videoId
                
                // Check if this video has enough views and get its details
                val videoDetails = getVideoDetails(youtubeService, videoId)
                if (videoDetails != null && hasEnoughViews(videoDetails)) {
                    val releaseYear = getVideoReleaseYear(videoDetails)
                    return@withContext VideoInfo(videoId, releaseYear)
                }
                
                // If the randomly chosen video doesn't have enough views, try to find one that does
                for (item in searchResponse.items) {
                    val id = item.id.videoId
                    val details = getVideoDetails(youtubeService, id)
                    if (details != null && hasEnoughViews(details)) {
                        val releaseYear = getVideoReleaseYear(details)
                        return@withContext VideoInfo(id, releaseYear)
                    }
                }
            }
            
            // Fallback to a guaranteed popular music video if the search fails
            return@withContext VideoInfo("dQw4w9WgXcQ", 1987) // Never Gonna Give You Up (1987)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching random video: ${e.message}", e)
            e.printStackTrace()
            return@withContext null
        }
    }
    
    private fun getVideoDetails(youtube: YouTube, videoId: String): Video? {
        return try {
            val videoRequest = youtube.videos().list(listOf("statistics", "snippet"))
            videoRequest.id = listOf(videoId)
            val response = videoRequest.execute()
            if (response.items.isNotEmpty()) response.items[0] else null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting video details: ${e.message}", e)
            e.printStackTrace()
            null
        }
    }
    
    private fun hasEnoughViews(video: Video): Boolean {
        val viewCount = try {
            video.statistics?.viewCount?.toLong() ?: 0
        } catch (e: NumberFormatException) {
            0
        }
        return viewCount >= 1_000_000 // At least 1 million views
    }
    
    private fun getVideoReleaseYear(video: Video): Int? {
        return try {
            val publishedAt = video.snippet?.publishedAt
            if (publishedAt != null) {
                // Extract the year from the publishedAt date (format: YYYY-MM-DDThh:mm:ss.sZ)
                val year = publishedAt.toString().substring(0, 4).toInt()
                return year
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting video release year: ${e.message}", e)
            null
        }
    }

    private fun getYouTubeService(): YouTube? {
        val account = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context) ?: return null
        
        val credential = com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential.usingOAuth2(
            context,
            listOf(com.google.api.services.youtube.YouTubeScopes.YOUTUBE_READONLY)
        )
        credential.selectedAccount = account.account
        
        return YouTube.Builder(transport, jsonFactory, credential)
            .setApplicationName(applicationName)
            .build()
    }
}