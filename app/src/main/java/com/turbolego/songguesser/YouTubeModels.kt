package com.turbolego.songguesser

/**
 * Represents a YouTube video with its metadata.
 */
data class YouTubeVideo(
    val videoId: String,
    val year: Int,
    val title: String
)

/**
 * Represents a video with its metadata (legacy name, kept for compatibility).
 */
@Deprecated("Use YouTubeVideo instead", ReplaceWith("YouTubeVideo"))
data class VideoInfo(
    val videoId: String,
    val year: Int,
    val title: String
)

/**
 * Response from YouTube search API.
 */
data class SearchResponse(
    val onScreenCommonConfigs: List<Config>? = null,
    val config: Config? = null
)

/**
 * Configuration object containing search results.
 */
data class Config(
    val searchResults: List<VideoResult>? = null
)

/**
 * Individual video result from search.
 */
data class VideoResult(
    val videoId: String = "",
    val videoTitle: String = "",
    val viewCountText: String = "",
    val shortViewCountText: String = ""
)

// InnerTubeService interface removed - functionality moved to util.YouTubeApiServiceImpl

/**
 * Video details from InnerTube API.
 */
data class VideoDetails(
    val videoId: String,
    val title: String,
    val publishedAt: String,
    val viewCount: String,
    val likeCount: String
)