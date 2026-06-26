package com.turbolego.songguesser

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.turbolego.songguesser.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for YouTube-related operations
 */
class YouTubeViewModel(application: Application) : AndroidViewModel(application) {
    private val logger = "YouTubeViewModel"
    
    private val youtubeApiService = YouTubeApiServiceImpl(
        apiKey = BuildConfig.YOUTUBE_API_KEY
    )
    
    private val _uiState = MutableStateFlow(YouTubeUiState())
    val uiState: StateFlow<YouTubeUiState> = _uiState.asStateFlow()
    
    fun searchVideos(query: String) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                
                val response = youtubeApiService.searchVideos(query = query)
                if (response.isSuccessful) {
                    val searchResponse = response.body() ?: return@launch
                    
                    // Extract video info from search results
                    val videoInfo = youtubeApiService.extractVideoInfo(searchResponse)
                    
                    if (videoInfo != null) {
                        // Create YouTubeVideo from VideoInfo
                        val youtubeVideo = YouTubeVideo(
                            id = videoInfo.id,
                            title = videoInfo.title,
                            url = "https://www.youtube.com/watch?v=${videoInfo.id}",
                            thumbnailUrl = "https://img.youtube.com/vi/${videoInfo.id}/default.jpg",
                            channelTitle = "Unknown Channel",
                            publishedAt = ""
                        )
                        
                        _uiState.value = _uiState.value.copy(
                            videos = listOf(youtubeVideo),
                            isLoading = false
                        )
                        Log.d(logger, "Search successful: ${videoInfo.title}")
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = "Failed to extract video information"
                        )
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Search failed: ${response.message()}"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Error: ${e.message}"
                )
                Log.e(logger, "Search error: ${e.message}", e)
            }
        }
    }
    
    fun getRandomMusicVideo() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                
                val response = youtubeApiService.getRandomMusicVideo()
                if (response.isSuccessful) {
                    val searchResponse = response.body() ?: return@launch
                    
                    // Extract video info from search results
                    val videoInfo = youtubeApiService.extractVideoInfo(searchResponse)
                    
                    if (videoInfo != null) {
                        // Create YouTubeVideo from VideoInfo
                        val youtubeVideo = YouTubeVideo(
                            id = videoInfo.id,
                            title = videoInfo.title,
                            url = "https://www.youtube.com/watch?v=${videoInfo.id}",
                            thumbnailUrl = "https://img.youtube.com/vi/${videoInfo.id}/default.jpg",
                            channelTitle = "Unknown Channel",
                            publishedAt = ""
                        )
                        
                        _uiState.value = _uiState.value.copy(
                            videos = listOf(youtubeVideo),
                            isLoading = false
                        )
                        Log.d(logger, "Got random music video: ${videoInfo.title}")
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = "Failed to extract video information"
                        )
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Search failed: ${response.message()}"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Error: ${e.message}"
                )
                Log.e(logger, "Get random video error: ${e.message}", e)
            }
        }
    }
}