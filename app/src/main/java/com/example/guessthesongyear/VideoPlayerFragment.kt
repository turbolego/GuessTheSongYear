package com.example.guessthesongyear

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.guessthesongyear.databinding.FragmentVideoPlayerBinding
import com.example.guessthesongyear.util.YouTubeApiService
import com.example.guessthesongyear.util.YouTubeAuthManager
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "VideoPlayerFragment"

class VideoPlayerFragment : Fragment() {

    private var _binding: FragmentVideoPlayerBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var authManager: YouTubeAuthManager
    private lateinit var youtubeApiService: YouTubeApiService
    private var countdownJob: Job? = null
    private var youTubePlayer: YouTubePlayer? = null
    private var currentVideoId: String? = null
    private var isPlayerReady = false
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVideoPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize services
        authManager = YouTubeAuthManager(requireContext())
        youtubeApiService = YouTubeApiService(requireContext())
        
        // Setup UI components
        setupSignOutButton()
        setupNextVideoButton()
        setupYouTubePlayer()
        
        // Show loading initially
        binding.progressLoading.visibility = View.VISIBLE
    }
    
    private fun setupYouTubePlayer() {
        Log.d(TAG, "Setting up YouTube player")
        
        // Important: Add the player view to lifecycle
        lifecycle.addObserver(binding.youtubePlayerView)
        
        // Create custom player options
        val options = IFramePlayerOptions.Builder()
            .controls(1)  // Show controls
            .fullscreen(1) // Enable fullscreen button
            .build()
        
        // Initialize player with options - this is manual initialization
        binding.youtubePlayerView.initialize(object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                Log.d(TAG, "YouTube player is ready")
                this@VideoPlayerFragment.youTubePlayer = youTubePlayer
                isPlayerReady = true
                
                // Once player is ready, load a video
                if (currentVideoId == null) {
                    loadRandomVideo()
                } else {
                    startCountdown(currentVideoId!!)
                }
            }
            
            override fun onError(youTubePlayer: YouTubePlayer, error: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerError) {
                Log.e(TAG, "YouTube player error: $error")
                binding.progressLoading.visibility = View.GONE
                
                Toast.makeText(
                    requireContext(),
                    "Error playing video: $error. Trying another video...",
                    Toast.LENGTH_SHORT
                ).show()
                
                // Try another video after a brief delay
                lifecycleScope.launch {
                    delay(1000)
                    loadRandomVideo()
                }
            }
        }, options)
    }
    
    private fun setupSignOutButton() {
        binding.buttonSignOut.setOnClickListener {
            Log.d(TAG, "Sign out button clicked")
            authManager.signOut {
                Log.d(TAG, "Sign out complete, navigating to login")
                navigateToLogin()
            }
        }
    }
    
    private fun setupNextVideoButton() {
        binding.buttonNextVideo.setOnClickListener {
            Log.d(TAG, "Next video button clicked")
            loadRandomVideo()
        }
    }
    
    private fun loadRandomVideo() {
        Log.d(TAG, "Loading random video")
        
        // Show loading indicator
        binding.progressLoading.visibility = View.VISIBLE
        binding.textViewCountdown.visibility = View.GONE
        
        // Cancel any existing countdown
        countdownJob?.cancel()
        
        // Get a random video
        lifecycleScope.launch {
            try {
                val videoId = youtubeApiService.getRandomMusicVideo()
                Log.d(TAG, "Random video ID: $videoId")
                
                if (videoId != null) {
                    // Store current video ID
                    currentVideoId = videoId
                    
                    // If player is ready, play this video
                    if (isPlayerReady) {
                        startCountdown(videoId)
                    }
                } else {
                    // No video found
                    Log.e(TAG, "Failed to get a random video")
                    Toast.makeText(
                        requireContext(),
                        R.string.error_no_video,
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.progressLoading.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading video: ${e.message}", e)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.error_no_video),
                    Toast.LENGTH_SHORT
                ).show()
                binding.progressLoading.visibility = View.GONE
            }
        }
    }
    
    private fun startCountdown(videoId: String) {
        Log.d(TAG, "Starting countdown before playing video: $videoId")
        
        binding.textViewCountdown.visibility = View.VISIBLE
        binding.progressLoading.visibility = View.GONE
        
        countdownJob = lifecycleScope.launch {
            for (i in 3 downTo 1) {
                binding.textViewCountdown.text = getString(R.string.video_countdown, i)
                delay(1000)
            }
            
            binding.textViewCountdown.visibility = View.GONE
            
            try {
                // Load the video when countdown completes
                youTubePlayer?.loadVideo(videoId, 0f)
                Log.d(TAG, "Video started playing: $videoId")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting video: ${e.message}", e)
                Toast.makeText(
                    requireContext(),
                    "Error playing video. Try another.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    private fun navigateToLogin() {
        Log.d(TAG, "Navigating to LoginFragment")
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, LoginFragment())
            .commit()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        // Cancel any pending operations
        countdownJob?.cancel()
        
        // Release YouTube player
        youTubePlayer = null
        isPlayerReady = false
        
        // Release binding
        _binding = null
    }
}