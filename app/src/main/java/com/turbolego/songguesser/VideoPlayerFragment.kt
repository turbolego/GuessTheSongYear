package com.turbolego.songguesser

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

private const val TAG = "VideoPlayerFragment"

/**
 * A known video with its release year (used as fallback).
 */
data class KnownVideo(val id: String, val year: Int)

/**
 * A video with its release year and view count (from API).
 */
data class ApiVideo(val id: String, val year: Int, val views: Long, val title: String)

/**
 * Fallback list of popular music videos with known release years.
 * Used when InnerTube search fails or returns no results.
 * These are well-known, stable videos that are consistently available on YouTube.
 */
private val fallbackVideoList = listOf(
    // 1980s
    KnownVideo("dQw4w9WgXcQ", 1987),       // Rick Astley - Never Gonna Give You Up
    KnownVideo("ZbZSe6N_BXs", 1985),       // Madonna - Material Girl
    KnownVideo("2Z8IUAiIufKN", 1983),      // Michael Jackson - Thriller
    KnownVideo("djV1KL4Btzw", 1984),       // Duran Duran - Hungry Like the Wolf
    KnownVideo("rYEDA3JiTEA", 1984),       // A-ha - Take On Me
    KnownVideo("1w7OgIMMRc4", 1985),       // Guns N' Roses - Sweet Child O' Mine
    KnownVideo("1G4isv_Fyls", 1985),       // Michael Jackson - Billie Jean
    KnownVideo("hr0ObGAUDlQ", 1982),       // Blondie - Rapture

    // 1990s
    KnownVideo("hcwnL9a61o0", 1999),       // Backstreet Boys - I Want It That Way
    KnownVideo("tF3iR0rN-8g", 1991),       // Nirvana - Smells Like Teen Spirit
    KnownVideo("q3zKKtYsEj8", 1994),       // Red Hot Chili Peppers - Give It Away
    KnownVideo("ZmDBbnMFK70", 1999),       // Spice Girls - Say You'll Be There
    KnownVideo("YR5W3FKE88Q", 1998),       // Vengaboys - Boom, Boom, Boom!!
    KnownVideo("fLexgOxsZu0", 1996),       // Spice Girls - Spice Up Your Life
    KnownVideo("XbGsChTe4go", 1999),       // Aqua - Barbie Girl
    KnownVideo("6KnRLJZ0ZRw", 1995),       // Spice Girls - Wannabe

    // 2000s
    KnownVideo("eBCRc2Zk6hA", 2000),       // OutKast - Hey Ya!
    KnownVideo("dQ1ribkayAU", 2008),       // Lady Gaga - Poker Face
    KnownVideo("lp-EO5I60KA", 2009),       // Eminem - Not Afraid
    KnownVideo("kJQP7kiw5Fk", 2006),       // Luis Miguel - No Me Importa

    // 2010s
    KnownVideo("9bZkp7q19f0", 2012),       // PSY - Gangnam Style
    KnownVideo("YQHsXMglC9A", 2015),       // Adele - Hello
    KnownVideo("OPf0YbXqDm0", 2014),       // Mark Ronson - Uptown Funk
    KnownVideo("2Vv-BfVoq4g", 2017),       // Ed Sheeran - Perfect
    KnownVideo("Rl6bfz9xYio", 2023),       // Tate McRae - Greedy
    KnownVideo("kPa7bsKwL-c", 2023),       // Steve Lacy - Bad Habit
    KnownVideo("hVlgHmeZjg8", 2021),       // BTS - Butter
    KnownVideo("ffxKSjUwZdU", 2021),       // Måneskin - Beggin'
    KnownVideo("QOQZRLdv3s0", 2018),       // The Weeknd - Call Out My Name
    KnownVideo("uelHwf8o7_U", 2018),       // Lady Gaga, Bradley Cooper - Shallow
    KnownVideo("Z09lZZd7aJs", 2020),       // Dua Lipa - Physical
    KnownVideo("nPLV7lGczsE", 2017),       // Dua Lipa - New Rules
    KnownVideo("GtMSnMlLiwY", 2019),       // Shawn Mendes, Camila Cabello - Señorita
    KnownVideo("u7K7pXAhK5c", 2015),       // Sam Smith - Stay With Me
    KnownVideo("bo_efYxQAse", 2018),       // Bruno Mars - Finesse
    KnownVideo("YVkKvmAVWHE", 2019),       // Billie Eilish - Bad Guy
    KnownVideo("YBHQbu5FpLk", 2020),       // Dua Lipa - Don't Start Now
    KnownVideo("1Q9qGcPp3b4", 2021),       // The Weeknd - Save Your Tears
    KnownVideo("456sX5lPcTQ", 2021),       // Olivia Rodrigo - Drivers License
    KnownVideo("b4Bj7Zb-YDc", 2021),       // Olivia Rodrigo - Good 4 U
    KnownVideo("pBk4NYvBMJc", 2022),       // Imagine Dragons - Bones
    KnownVideo("W0DM0WCb5ac", 2023),       // Miley Cyrus - Flowers
    KnownVideo("iWzVlFouYwE", 2023),       // Sam Smith, Kim Petras - Unholy
)

/**
 * Current list of videos to use (API results or fallback).
 */
private var currentVideoList: MutableList<ApiVideo> = mutableListOf()

/**
 * Fetch videos from InnerTube API and update currentVideoList.
 * Uses hardcoded list as fallback if API fails.
 */
private suspend fun fetchVideosFromApi() {
    Log.d(TAG, "Fetching videos from InnerTube API...")
    
    val apiVideos = try {
        YouTubeSearchService.searchMusicVideos()
    } catch (e: Exception) {
        Log.e(TAG, "API search failed, using fallback list", e)
        emptyList()
    }
    
    if (apiVideos.isNotEmpty()) {
        Log.d(TAG, "Successfully fetched ${apiVideos.size} videos from API")
        currentVideoList.clear()
        currentVideoList.addAll(apiVideos)
    } else {
        Log.w(TAG, "No videos from API, using fallback list (${fallbackVideoList.size} videos)")
        currentVideoList.clear()
        currentVideoList.addAll(fallbackVideoList.map { ApiVideo(it.id, it.year, 0, "") })
    }
}

class VideoPlayerFragment : Fragment() {

    private lateinit var progressBar: ProgressBar
    private lateinit var youtubePlayerView: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
    private lateinit var textViewCountdown: TextView
    private lateinit var textViewSongYear: TextView
    private lateinit var buttonNextVideo: Button

    private var loadVideoJob: Job? = null
    private var countdownJob: Job? = null
    private var youTubePlayer: YouTubePlayer? = null
    private var currentVideo: ApiVideo? = null
    private var isPlayerReady = false
    private var isVideoLoaded = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup UI components
        setupNextVideoButton()
        setupYouTubePlayer()

        // Fetch videos from API when fragment is created
        lifecycleScope.launch {
            fetchVideosFromApi()
        }

        // Show loading initially
        progressBar.visibility = View.VISIBLE
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_video_player, container, false)

        // Initialize views using findViewById
        progressBar = view.findViewById(R.id.progressBar)
        youtubePlayerView = view.findViewById(R.id.youtube_player_view)
        textViewCountdown = view.findViewById(R.id.textViewCountdown)
        textViewSongYear = view.findViewById(R.id.textViewSongYear)
        buttonNextVideo = view.findViewById(R.id.buttonNextVideo)

        return view
    }

    private fun setupYouTubePlayer() {
        Log.d(TAG, "Setting up YouTube player")

        // Important: Add the player view to lifecycle
        lifecycle.addObserver(youtubePlayerView)

        // Create custom player options
        val options = IFramePlayerOptions.Builder(requireContext())
            .controls(1)       // Show controls
            .fullscreen(1)     // Enable fullscreen button
            .build()

        // Initialize player with options
        youtubePlayerView.initialize(object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                Log.d(TAG, "YouTube player is ready")
                this@VideoPlayerFragment.youTubePlayer = youTubePlayer
                isPlayerReady = true

                // Once player is ready, load a video
                if (currentVideo == null) {
                    loadRandomVideo()
                } else {
                    startCountdown(currentVideo!!.id)
                }
            }

            override fun onStateChange(
                youTubePlayer: YouTubePlayer,
                state: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerState
            ) {
                // When video state changes to PLAYING, show the release year
                if (state == com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerState.PLAYING) {
                    val vid = currentVideo?.id ?: "unknown"
                    Log.d(TAG, "Video playback started: $vid")
                    isVideoLoaded = true
                    showReleaseYear()
                }
            }

            override fun onError(
                youTubePlayer: YouTubePlayer,
                error: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerError
            ) {
                val errorCode = error.ordinal
                val errorName = error.name
                Log.e(TAG, "YouTube player error: code=$errorCode, name=$errorName")

                progressBar.visibility = View.GONE
                isVideoLoaded = false

                // Try another video after a brief delay
                lifecycleScope.launch {
                    delay(1.5.seconds)
                    loadRandomVideo()
                }
            }
        }, options)
    }

    private fun setupNextVideoButton() {
        buttonNextVideo.setOnClickListener {
            Log.d(TAG, "Next video button clicked")
            loadRandomVideo()
        }
    }

    private fun loadRandomVideo() {
        Log.d(TAG, "Loading random video from pool (${currentVideoList.size} videos)")

        // Show loading indicator
        progressBar.visibility = View.VISIBLE
        textViewCountdown.visibility = View.GONE
        textViewSongYear.visibility = View.GONE
        isVideoLoaded = false

        // Cancel any existing countdown and load video job
        countdownJob?.cancel()
        loadVideoJob?.cancel()

        // Pick a random video from the pool
        loadVideoJob = lifecycleScope.launch {
            try {
                val video = currentVideoList[Random.nextInt(currentVideoList.size)]
                Log.d(TAG, "Selected video: ${video.id}, Year: ${video.year}, Views: ${video.views}")

                currentVideo = video

                // If player is ready, play this video
                if (isPlayerReady) {
                    startCountdown(video.id)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading video: ${e.message}", e)
                Toast.makeText(
                    requireContext(),
                    "Error loading video. Try another.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun startCountdown(videoId: String) {
        Log.d(TAG, "Starting countdown before playing video: $videoId")

        textViewCountdown.visibility = View.VISIBLE
        progressBar.visibility = View.GONE

        countdownJob = lifecycleScope.launch {
            for (i in 3 downTo 1) {
                textViewCountdown.text = getString(R.string.video_countdown, i)
                delay(1.seconds)
            }

            textViewCountdown.visibility = View.GONE

            try {
                // Load the video when countdown completes
                youTubePlayer?.loadVideo(videoId, 0f)
                Log.d(TAG, "Video load command sent: $videoId")
                // Note: showReleaseYear() is called from onStateChange callback
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

    private fun showReleaseYear() {
        currentVideo?.let { video ->
            textViewSongYear.text = getString(R.string.song_release_year, video.year)
        } ?: run {
            textViewSongYear.text = getString(R.string.year_unknown)
        }
        textViewSongYear.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Cancel any pending operations
        countdownJob?.cancel()
        loadVideoJob?.cancel()

        // Release YouTube player
        youTubePlayer = null
        isPlayerReady = false
    }
}