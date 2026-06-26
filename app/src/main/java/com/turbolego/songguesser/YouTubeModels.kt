package com.turbolego.songguesser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Data class for YouTube search response
 */
data class SearchResponse(
    val kind: String = "youtube#searchListResponse",
    val etag: String = "",
    val nextPageToken: String = "",
    val regionCode: String = "US",
    val items: List<SearchResult> = emptyList()
)

/**
 * Data class for a single search result
 */
data class SearchResult(
    val kind: String = "youtube#search",
    val etag: String = "",
    val id: SearchResultId,
    val snippet: SearchSnippet
)

/**
 * Data class for search result ID
 */
data class SearchResultId(
    val kind: String = "youtube#video",
    val videoId: String
)

/**
 * Data class for search snippet
 */
data class SearchSnippet(
    val publishedAt: String = "",
    val channelId: String = "",
    val title: String = "",
    val description: String = "",
    val thumbnailUrl: String = "",
    val channelTitle: String = "",
    val liveBroadcastContent: String = "",
    val publishTime: String = ""
)

/**
 * Data class for video information including release year
 */
data class VideoInfo(
    val id: String,
    val title: String,
    val description: String,
    val releaseYear: Int?
)

/**
 * Data class for YouTube video in UI
 */
data class YouTubeVideo(
    val id: String,
    val title: String,
    val url: String,
    val thumbnailUrl: String,
    val channelTitle: String,
    val publishedAt: String
)

/**
 * UI state for YouTube fragment
 */
data class YouTubeUiState(
    val videos: List<YouTubeVideo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)