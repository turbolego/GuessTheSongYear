package com.turbolego.songguesser

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.turbolego.songguesser.databinding.FragmentVideoPlayerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

private const val TAG = "VideoPlayerFragment"

/** A known video with its release year (fallback). */
data class KnownVideo(val id: String, val year: Int)

/** A video with metadata from API or fallback. */
data class ApiVideo(val id: String, val year: Int, val views: Long, val title: String)

/**
 * Fallback list of popular music videos with known release years.
 * Used when InnerTube search fails.
 */
private val fallbackVideoList = listOf(
    KnownVideo("dQw4w9WgXcQ", 1987),
    KnownVideo("ZbZSe6N_BXs", 1985),
    KnownVideo("djV1KL4Btzw", 1984),
    KnownVideo("rYEDA3JiTEA", 1984),
    KnownVideo("1w7OgIMMRc4", 1985),
    KnownVideo("1G4isv_Fyls", 1985),
    KnownVideo("hr0ObGAUDlQ", 1982),
    KnownVideo("hcwnL9a61o0", 1999),
    KnownVideo("tF3iR0rN-8g", 1991),
    KnownVideo("q3zKKtYsEj8", 1994),
    KnownVideo("ZmDBbnMFK70", 1999),
    KnownVideo("YR5W3FKE88Q", 1998),
    KnownVideo("XbGsChTe4go", 1999),
    KnownVideo("6KnRLJZ0ZRw", 1995),
    KnownVideo("eBCRc2Zk6hA", 2000),
    KnownVideo("dQ1ribkayAU", 2008),
    KnownVideo("lp-EO5I60KA", 2009),
    KnownVideo("kJQP7kiw5Fk", 2006),
    KnownVideo("9bZkp7q19f0", 2012),
    KnownVideo("YQHsXMglC9A", 2015),
    KnownVideo("OPf0YbXqDm0", 2014),
    KnownVideo("2Vv-BfVoq4g", 2017),
    KnownVideo("Rl6bfz9xYio", 2023),
    KnownVideo("kPa7bsKwL-c", 2023),
    KnownVideo("hVlgHmeZjg8", 2021),
    KnownVideo("ffxKSjUwZdU", 2021),
    KnownVideo("QOQZRLdv3s0", 2018),
    KnownVideo("uelHwf8o7_U", 2019),
    KnownVideo("Z09lZZd7aJs", 2020),
    KnownVideo("nPLV7lGczsE", 2017),
    KnownVideo("YVkKvmAVWHE", 2019),
    KnownVideo("YBHQbu5FpLk", 2020),
    KnownVideo("1Q9qGcPp3b4", 2021),
    KnownVideo("456sX5lPcTQ", 2021),
    KnownVideo("b4Bj7Zb-YDc", 2021),
    KnownVideo("pBk4NYvBMJc", 2022),
    KnownVideo("W0DM0WCb5ac", 2023),
    KnownVideo("iWzVlFouYwE", 2023),
)

/** Current video pool. */
private var currentVideoList: MutableList<ApiVideo> = mutableListOf()

/**
 * Fetches music videos from InnerTube API, falling back to hardcoded list.
 */
private suspend fun fetchVideosFromApi() {
    Log.d(TAG, "Fetching videos from InnerTube API...")
    val apiVideos = try {
        YouTubeSearchService.searchMusicVideos()
    } catch (e: Exception) {
        Log.e(TAG, "API search failed", e)
        emptyList()
    }
    if (apiVideos.isNotEmpty()) {
        Log.d(TAG, "Got ${apiVideos.size} videos from API")
        currentVideoList.clear()
        currentVideoList.addAll(apiVideos)
    } else {
        Log.w(TAG, "Using fallback (${fallbackVideoList.size} videos)")
        currentVideoList.clear()
        currentVideoList.addAll(fallbackVideoList.map { ApiVideo(it.id, it.year, 0, "") })
    }
}

class VideoPlayerFragment : Fragment() {

    private var _binding: FragmentVideoPlayerBinding? = null
    private val binding get() = _binding!!

    private var youTubePlayer: YouTubePlayer? = null
    private var currentVideo: ApiVideo? = null
    private var isPlayerReady = false
    private var hasGuessedThisRound = false
    private var currentDifficulty: Difficulty = Difficulty.MEDIUM

    private var countdownJob: Job? = null
    private var loadVideoJob: Job? = null

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

        setupListeners()
        setupYouTubePlayer()

        lifecycleScope.launch {
            fetchVideosFromApi()
        }

        updateScoreDisplay()
        binding.progressBar.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        countdownJob?.cancel()
        loadVideoJob?.cancel()
        youTubePlayer = null
        isPlayerReady = false
        _binding = null
    }

    // ── Difficulty ──────────────────────────────────────────────────────────

    fun setDifficulty(difficulty: Difficulty) {
        currentDifficulty = difficulty
    }

    // ── UI Setup ────────────────────────────────────────────────────────────

    private fun setupListeners() {
        // Guess button
        binding.buttonGuess.setOnClickListener {
            submitGuess()
        }

        // Enter key in edit text
        binding.editTextGuess.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitGuess()
                true
            } else false
        }

        // Enable guess button when text is present
        binding.editTextGuess.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.buttonGuess.isEnabled = !s.isNullOrBlank()
            }
        })

        // Next video
        binding.buttonNextVideo.setOnClickListener {
            loadRandomVideo()
        }
    }

    private fun setupYouTubePlayer() {
        lifecycle.addObserver(binding.youtubePlayerView)

        val options = IFramePlayerOptions.Builder(requireContext())
            .controls(1)
            .fullscreen(1)
            .build()

        binding.youtubePlayerView.initialize(object : AbstractYouTubePlayerListener() {
            override fun onReady(yp: YouTubePlayer) {
                youTubePlayer = yp
                isPlayerReady = true
                if (currentVideo == null) loadRandomVideo()
                else startCountdown(currentVideo!!.id)
            }

            override fun onStateChange(
                yp: YouTubePlayer,
                state: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerState
            ) {
                if (state == com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerState.PLAYING) {
                    val vid = currentVideo?.id ?: "unknown"
                    Log.d(TAG, "Video started: $vid")
                    // Enable guessing when video starts
                    binding.editTextGuess.isEnabled = true
                    binding.editTextGuess.requestFocus()
                    if (currentDifficulty.hintEnabled) showHint()
                }
            }

            override fun onError(
                yp: YouTubePlayer,
                error: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerError
            ) {
                Log.e(TAG, "Player error: ${error.name}")
                binding.progressBar.visibility = View.GONE
                lifecycleScope.launch {
                    delay(1.5.seconds)
                    loadRandomVideo()
                }
            }
        }, options)
    }

    // ── Hint System ─────────────────────────────────────────────────────────

    private fun showHint() {
        currentVideo?.let { video ->
            val decade = (video.year / 10) * 10
            val viewsText = when {
                video.views >= 1_000_000_000L -> "${video.views / 1_000_000_000} milliarder"
                video.views >= 1_000_000L -> "${video.views / 1_000_000}M"
                video.views >= 1_000L -> "${video.views / 1_000}K"
                else -> "${video.views}"
            }
            binding.textViewHint.text = getString(R.string.hint_decade, decade)
            if (video.views > 0) {
                binding.textViewHint.append(" | ${getString(R.string.hint_views, video.views)}")
            }
            binding.textViewHint.visibility = View.VISIBLE
        }
    }

    // ── Game Flow ───────────────────────────────────────────────────────────

    private fun submitGuess() {
        if (hasGuessedThisRound) return

        val guessText = binding.editTextGuess.text.toString().trim()
        if (guessText.length != 4) {
            Toast.makeText(requireContext(), R.string.error_invalid_year, Toast.LENGTH_SHORT).show()
            return
        }

        val guessedYear = guessText.toIntOrNull()
        if (guessedYear == null || guessedYear !in 1960..2025) {
            Toast.makeText(requireContext(), R.string.error_invalid_year, Toast.LENGTH_SHORT).show()
            return
        }

        val video = currentVideo ?: return
        hasGuessedThisRound = true
        binding.editTextGuess.isEnabled = false
        binding.buttonGuess.isEnabled = false

        // Evaluate
        val result = ScoreManager.evaluateGuess(guessedYear, video.year, currentDifficulty)

        // Show feedback
        val fbText = if (result.pointsEarned > 0) {
            val feedback = getString(result.messageResId, *result.messageArgs.toTypedArray())
            "$feedback\n${getString(R.string.score_earned, result.pointsEarned)}"
        } else {
            getString(result.messageResId, *result.messageArgs.toTypedArray())
        }
        binding.textViewFeedback.text = fbText
        binding.textViewFeedback.setTextColor(
            if (result.isCorrect) resources.getColor(R.color.green_correct, null)
            else resources.getColor(R.color.red_wrong, null)
        )
        binding.textViewFeedback.visibility = View.VISIBLE

        // Show the actual year
        binding.textViewSongYear.text = getString(R.string.song_release_year, video.year)
        binding.textViewSongYear.visibility = View.VISIBLE

        // Show next button
        binding.buttonNextVideo.visibility = View.VISIBLE

        updateScoreDisplay()
    }

    private fun loadRandomVideo() {
        Log.d(TAG, "Loading random video (${currentVideoList.size} in pool)")

        // Reset UI
        binding.progressBar.visibility = View.VISIBLE
        binding.textViewCountdown.visibility = View.GONE
        binding.textViewHint.visibility = View.GONE
        binding.textViewSongYear.visibility = View.GONE
        binding.textViewFeedback.visibility = View.GONE
        binding.buttonNextVideo.visibility = View.GONE
        binding.editTextGuess.isEnabled = false
        binding.editTextGuess.setText("")
        binding.buttonGuess.isEnabled = false
        hasGuessedThisRound = false

        countdownJob?.cancel()
        loadVideoJob?.cancel()

        loadVideoJob = lifecycleScope.launch {
            try {
                // Filter by difficulty range
                val filtered = currentVideoList.filter {
                    it.year in currentDifficulty.yearRangeStart..currentDifficulty.yearRangeEnd
                }
                val pool = if (filtered.isEmpty()) currentVideoList else filtered
                val video = pool[Random.nextInt(pool.size)]
                Log.d(TAG, "Selected: ${video.id} (${video.year})")
                currentVideo = video

                if (isPlayerReady) {
                    startCountdown(video.id)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading video", e)
                Toast.makeText(requireContext(), R.string.error_loading_video, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCountdown(videoId: String) {
        binding.textViewCountdown.visibility = View.VISIBLE
        binding.progressBar.visibility = View.GONE

        countdownJob = lifecycleScope.launch {
            for (i in 3 downTo 1) {
                binding.textViewCountdown.text = getString(R.string.video_countdown, i)
                delay(1.seconds)
            }
            binding.textViewCountdown.visibility = View.GONE
            try {
                youTubePlayer?.loadVideo(videoId, 0f)
                Log.d(TAG, "Playing: $videoId")
            } catch (e: Exception) {
                Log.e(TAG, "Error playing video", e)
                Toast.makeText(requireContext(), R.string.error_loading_video, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateScoreDisplay() {
        binding.textViewScore.text = buildString {
            append("Score: ${ScoreManager.score}")
            if (ScoreManager.streak > 0) {
                append("  |  Streak: ${ScoreManager.streak}")
            }
        }
    }

    // ── Public API for MainActivity ──────────────────────────────────────────

    fun resetScore() {
        ScoreManager.reset()
        updateScoreDisplay()
    }

    fun getStats(): String = buildString {
        append("Totalt: ${ScoreManager.guessCount} gjetninger")
        append("\nRiktige: ${ScoreManager.correctCount} (${(ScoreManager.accuracy * 100).toInt()}%)")
        append("\nHøyeste streak: ${ScoreManager.highStreak}")
        append("\nPoeng totalt: ${ScoreManager.score}")
    }
}
