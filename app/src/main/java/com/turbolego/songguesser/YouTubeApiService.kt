package com.turbolego.songguesser

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * Service interface for YouTube Data API v3
 */
interface YouTubeApiService {
    /**
     * Search for YouTube videos based on query
     */
    @GET("search")
    suspend fun searchVideos(
        @Query("part") part: String = "snippet",
        @Query("q") query: String,
        @Query("type") type: String = "video",
        @Query("maxResults") maxResults: Int = 5,
        @Query("key") apiKey: String
    ): Response<SearchResponse>

    /**
     * Get a random music video from the 80s
     */
    @GET("search")
    suspend fun getRandomMusicVideo(
        @Query("part") part: String = "snippet",
        @Query("q") query: String = "80s music",
        @Query("type") type: String = "video",
        @Query("maxResults") maxResults: Int = 1,
        @Query("key") apiKey: String
    ): Response<SearchResponse>
}

/**
 * Implementation of YouTubeApiService
 */
class YouTubeApiServiceImpl(
    private val baseUrl: String = "",
    private val apiKey: String
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl("https://www.googleapis.com/youtube/v3/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val service: YouTubeApiService = retrofit.create(YouTubeApiService::class.java)

    suspend fun searchVideos(
        part: String = "snippet",
        query: String,
        type: String = "video",
        maxResults: Int = 5,
        apiKey: String = this.apiKey
    ): Response<SearchResponse> {
        return service.searchVideos(part, query, type, maxResults, apiKey)
    }

    suspend fun getRandomMusicVideo(
        part: String = "snippet",
        query: String = "80s music",
        type: String = "video",
        maxResults: Int = 1,
        apiKey: String = this.apiKey
    ): Response<SearchResponse> {
        return service.getRandomMusicVideo(part, query, type, maxResults, apiKey)
    }

    suspend fun extractVideoInfo(searchResponse: SearchResponse): VideoInfo? {
        return searchResponse.items.firstOrNull()?.let { result ->
            val videoId = result.id.videoId
            val title = result.snippet.title
            val description = result.snippet.description
            val publishedAt = result.snippet.publishedAt

            // Extract year from publishedAt (format: "2020-01-01T00:00:00Z")
            val releaseYear = extractYearFromPublishedAt(publishedAt)

            VideoInfo(
                id = videoId,
                title = title,
                description = description,
                releaseYear = releaseYear
            )
        }
    }

    private fun extractYearFromPublishedAt(publishedAt: String): Int? {
        return try {
            // Format: "2020-01-01T00:00:00Z"
            val year = publishedAt.substring(0, 4)
            year.toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }
}