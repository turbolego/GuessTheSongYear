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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

private const val TAG = "VideoPlayerFragment"
private const val MAX_RETRY_ATTEMPTS = 5

data class KnownVideo(val id: String, val year: Int)
data class ApiVideo(val id: String, val year: Int, val views: Long, val title: String)

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
    KnownVideo("ZmDBbnMqK70", 1999),
    KnownVideo("YR5W3FKE88Q", 1998),
    KnownVideo("XbGsChTe4go", 1999),
    KnownVideo("6KnRLJZ0ZRw", 1995),
    KnownVideo("eBCRc2Zk6hA", 2000),
    KnownVideo("dQ1ribkayAU", 2008),
    KnownVideo("lp-EO5I60KA", 2009),
    KnownVideo("kJQP7kiF5Fk", 2006),
    KnownVideo("9bZkp7q19f0", 2012),
    KnownVideo("YQHsXMglC9A", 2015),
    KnownVideo("OPf0YbXqDm0", 2014),
    KnownVideo("2Vv-BfVoq4g", 2017),
    KnownVideo("R1Bfz9xYio", 2023),
    KnownVideo("kPa7bsDwL-c", 2023),
    KnownVideo("hVlgHmeZjg8", 2021),
    KnownVideo("ffxKSjUtwZU", 2021),
    KnownVideo("QOZZRLdv3s0", 2018),
    KnownVideo("uelHdwf8o7U", 2019),
    KnownVideo("Z09lZZd7aJs", 2020),
    KnownVideo("nPLV7lGczsE", 2017),
    KnownVideo("YVkKvmAVWHE", 2019),
    KnownVideo("YBHQbu5FpLk", 2020),
    KnownVideo("1q9XplKp3G4", 2021),
    KnownVideo("4t6sX5dD4cT", 2021),
    KnownVideo("b4Bj7Zb-YDc", 2021),
    KnownVideo("pBk4LyvAMJc", 2022),
    KnownVideo("W0DW0WCb5ac", 2023),
    KnownVideo("iWzvlFnyYwE", 2023),
)

private var currentVideoList: MutableList<ApiVideo> = mutableListOf()
val playedVideoIds: MutableSet<String> = mutableSetOf()
var duplicateSkipCount = 0

private suspend fun fetchVideosFromApi() {
    Log.d(TAG, "Fetching videos from InnerTube API...")
    val apiVideos = try {
        YouTubeSearchService.searchMusicVideos()
    } catch (e: Exception) {
        Log.e(TAG, "API search failed", e)
        emptyList()
    }
    if (apiVideos.isNotEmpty()) {
        Log.d(TAG, "Got ${apiVideos.size} from API")
        currentVideoList.clear()
        currentVideoList.addAll(apiVideos)
    } else {
        Log.w(TAG, "Using fallback (${fallbackVideoList.size} videos)")
        currentVideoList.clear()
        currentVideoList.addAll(fallbackVideoList.map { ApiVideo(it.id, it.year, 0, "") })
    }
}

private fun pickCandidate(difficulty: Difficulty): ApiVideo? {
    val pool = currentVideoList.filter {
        it.year in difficulty.yearRangeStart..difficulty.yearRangeEnd
    }
    if (pool.isEmpty()) return null
    val unplayed = pool.filter { it.id !in playedVideoIds }
    if (unplayed.isEmpty()) {
        Log.d(TAG, "All ${pool.size} videos played — resetting session")
        playedVideoIds.clear()
        duplicateSkipCount = 0
        return pool.random()
    }
    return unplayed.random()
}

class VideoPlayerFragment : Fragment() {

    private var _binding: FragmentVideoPlayerBinding? = null
    private val binding get() = _binding!!

    private var youTubePlayer: YouTubePlayer? = null
    private var currentVideo: ApiVideo? = null
    private var isPlayerReady = false
    private var hasGuessedThisRound = false
    private var currentDifficulty: Difficulty = Difficulty.MEDIUM
    private var errorRetryCount = 0

    private var countdownJob: Job? = null
    private var loadVideoJob: Job? = null

    // ── Multiplayer state ────────────────────────────────────────────────────
    private var isMultiplayer = false
    private var mpPlayerNames: List<String> = emptyList()
    private var mpCurrentIndex = 0
    /** How many distinct players have submitted a guess for the current video. */
    private var mpGuessedThisRound = 0

    private fun currentPlayerName(): String =
        if (isMultiplayer && mpPlayerNames.isNotEmpty()) mpPlayerNames[mpCurrentIndex]
        else ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { args ->
            val names = args.getStringArrayList(ARG_PLAYER_NAMES)
            if (!names.isNullOrEmpty()) {
                isMultiplayer = true
                mpPlayerNames = names
                mpCurrentIndex = 0
                mpGuessedThisRound = 0
                // Seed MultiPlayerManager with these players
                MultiPlayerManager.clear()
                names.forEach { name ->
                    if (name.isNotBlank()) MultiPlayerManager.addPlayer(name)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentVideoPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupListeners()
        setupYouTubePlayer()
        updateMultiplayerUI()

        lifecycleScope.launch { fetchVideosFromApi() }

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

    fun setDifficulty(difficulty: Difficulty) { currentDifficulty = difficulty }

    // ── UI Setup ────────────────────────────────────────────────────────────

    private fun setupListeners() {
        binding.buttonGuess.setOnClickListener { submitGuess() }

        binding.editTextGuess.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submitGuess(); true } else false
        }

        binding.editTextGuess.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.buttonGuess.isEnabled = !s.isNullOrBlank()
            }
        })

        binding.buttonNextVideo.setOnClickListener {
            if (isMultiplayer && mpGuessedThisRound < mpPlayerNames.size) {
                // More players need to guess the current video
                advanceToNextPlayer()
            } else {
                loadNextVideo()
            }
        }

        // Video overlay toggle
        var overlayVisible = false
        binding.buttonToggleVideo.setOnClickListener {
            overlayVisible = !overlayVisible
            binding.videoOverlay.visibility = if (overlayVisible) View.VISIBLE else View.GONE
            binding.textViewOverlayLabel.visibility = if (overlayVisible) View.VISIBLE else View.GONE
            binding.buttonToggleVideo.text = getString(
                if (overlayVisible) R.string.btn_show_video else R.string.btn_hide_video
            )
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
                if (currentVideo == null) loadNextVideo()
                else beginCountdown(currentVideo!!.id)
            }

            override fun onStateChange(
                yp: YouTubePlayer,
                state: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerState,
            ) {
                if (state == com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerState.PLAYING) {
                    errorRetryCount = 0
                    Log.d(TAG, "Playing: ${currentVideo?.id}")
                    binding.editTextGuess.isEnabled = true
                    binding.editTextGuess.requestFocus()
                    if (currentDifficulty.hintEnabled) showHint()
                }
            }

            override fun onError(
                yp: YouTubePlayer,
                error: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerError,
            ) {
                Log.e(TAG, "Player error: ${error.name} (attempt ${errorRetryCount + 1})")
                errorRetryCount++

                if (errorRetryCount >= MAX_RETRY_ATTEMPTS) {
                    Toast.makeText(requireContext(), R.string.error_loading_video, Toast.LENGTH_LONG).show()
                    binding.buttonNextVideo.visibility = View.VISIBLE
                    binding.progressBar.visibility = View.GONE
                    return
                }

                lifecycleScope.launch {
                    val candidate = pickCandidate(currentDifficulty)
                    if (candidate != null) {
                        Log.d(TAG, "Auto-switching to: ${candidate.id}")
                        currentVideo = candidate
                        playedVideoIds.add(candidate.id)
                        try {
                            youTubePlayer?.loadVideo(candidate.id, 0f)
                        } catch (_: Exception) {}
                    } else {
                        Toast.makeText(requireContext(), R.string.error_loading_video, Toast.LENGTH_LONG).show()
                        binding.buttonNextVideo.visibility = View.VISIBLE
                        binding.progressBar.visibility = View.GONE
                    }
                }
            }
        }, options)
    }

    // ── Hint System ─────────────────────────────────────────────────────────

    private fun showHint() {
        currentVideo?.let { video ->
            val decade = (video.year / 10) * 10
            binding.textViewHint.text = getString(R.string.hint_decade, decade)
            if (video.views > 0) {
                binding.textViewHint.append(" | ${getString(R.string.hint_views, video.views)}")
            }
            binding.textViewHint.visibility = View.VISIBLE
        }
    }

    // ── Multiplayer UI ──────────────────────────────────────────────────────

    private fun updateMultiplayerUI() {
        if (isMultiplayer) {
            binding.textViewCurrentPlayer.text = getString(R.string.turn_of, currentPlayerName())
            binding.textViewCurrentPlayer.visibility = View.VISIBLE
            binding.textViewLeaderboardLabel.visibility = View.VISIBLE
            binding.textViewLeaderboard.visibility = View.VISIBLE
            refreshLeaderboard()
        } else {
            binding.textViewCurrentPlayer.visibility = View.GONE
            binding.textViewLeaderboardLabel.visibility = View.GONE
            binding.textViewLeaderboard.visibility = View.GONE
        }
    }

    private fun refreshLeaderboard() {
        if (!isMultiplayer) return
        val players = MultiPlayerManager.getLeaderboard()
        val sb = StringBuilder()
        players.forEachIndexed { i, p ->
            when (i) {
                0 -> sb.append("🥇 ")
                1 -> sb.append("🥈 ")
                2 -> sb.append("🥉 ")
                else -> sb.append("${i + 1}. ")
            }
            sb.append("${p.name} — ${p.score} poeng")
            if (p.name == currentPlayerName()) sb.append(" ◀")
            sb.append("\n")
        }
        binding.textViewLeaderboard.text = sb.toString()
    }

    private fun advanceToNextPlayer() {
        mpCurrentIndex = (mpCurrentIndex + 1) % mpPlayerNames.size
        hasGuessedThisRound = false
        binding.textViewCurrentPlayer.text = getString(R.string.turn_of, currentPlayerName())
        binding.textViewFeedback.visibility = View.GONE
        binding.textViewSongYear.visibility = View.GONE
        binding.editTextGuess.isEnabled = true
        binding.editTextGuess.setText("")
        binding.editTextGuess.requestFocus()
        binding.buttonGuess.isEnabled = false
        binding.buttonNextVideo.visibility = View.GONE
        refreshLeaderboard()
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

        val result: ScoreManager.GuessResult

        if (isMultiplayer) {
            val playerName = currentPlayerName()
            MultiPlayerManager.recordGuess(playerName, guessedYear, video.year, currentDifficulty)
            val player = MultiPlayerManager.allPlayers.find { it.name == playerName }
            result = player?.guessResult ?: ScoreManager.evaluateGuess(guessedYear, video.year, currentDifficulty)
            mpGuessedThisRound++
        } else {
            result = ScoreManager.evaluateGuess(guessedYear, video.year, currentDifficulty)
        }

        val fbText = if (result.pointsEarned > 0) {
            "${getString(result.messageResId, *result.messageArgs.toTypedArray())}\n${getString(R.string.score_earned, result.pointsEarned)}"
        } else {
            getString(result.messageResId, *result.messageArgs.toTypedArray())
        }
        binding.textViewFeedback.text = fbText
        binding.textViewFeedback.setTextColor(
            resources.getColor(if (result.isCorrect) R.color.green_correct else R.color.red_wrong, null)
        )
        binding.textViewFeedback.visibility = View.VISIBLE

        binding.textViewSongYear.text = getString(R.string.song_release_year, video.year)
        binding.textViewSongYear.visibility = View.VISIBLE

        // Show appropriate next button
        val allHaveGuessed = !isMultiplayer || mpGuessedThisRound >= mpPlayerNames.size
        if (isMultiplayer && !allHaveGuessed) {
            binding.buttonNextVideo.text = getString(R.string.next_player)
        } else if (isMultiplayer && allHaveGuessed) {
            binding.buttonNextVideo.text = getString(R.string.next_video)
            // Reset for next round
            mpGuessedThisRound = 0
            mpCurrentIndex = 0
        } else {
            binding.buttonNextVideo.text = getString(R.string.next_video)
        }
        binding.buttonNextVideo.visibility = View.VISIBLE

        updateScoreDisplay()
        if (isMultiplayer) refreshLeaderboard()
    }

    private fun loadNextVideo() {
        Log.d(TAG, "Loading next video (pool=${currentVideoList.size})")
        resetRoundUI()

        countdownJob?.cancel()
        loadVideoJob?.cancel()

        loadVideoJob = lifecycleScope.launch {
            try {
                val candidate = pickCandidate(currentDifficulty)
                if (candidate == null) {
                    Toast.makeText(requireContext(), R.string.error_loading_video, Toast.LENGTH_SHORT).show()
                    return@launch
                }

                currentVideo = candidate
                playedVideoIds.add(candidate.id)

                if (isPlayerReady) {
                    beginCountdown(candidate.id)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading video", e)
                Toast.makeText(requireContext(), R.string.error_loading_video, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun beginCountdown(videoId: String) {
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
            } catch (e: Exception) {
                Log.e(TAG, "loadVideo threw", e)
                Toast.makeText(requireContext(), R.string.error_loading_video, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resetRoundUI() {
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
        errorRetryCount = 0

        if (isMultiplayer) {
            mpGuessedThisRound = 0
            mpCurrentIndex = 0
            // Show first player's turn
            binding.textViewCurrentPlayer.text = getString(R.string.turn_of, currentPlayerName())
        }
    }

    private fun updateScoreDisplay() {
        if (isMultiplayer) {
            binding.textViewScore.text = buildString {
                append("Runde: ${ScoreManager.guessCount + 1}")
            }
        } else {
            binding.textViewScore.text = buildString {
                append("Score: ${ScoreManager.score}")
                if (ScoreManager.streak > 0) append("  |  Streak: ${ScoreManager.streak}")
            }
        }
    }

    // ── Public API for MainActivity ──────────────────────────────────────────

    fun isMultiplayerMode(): Boolean = isMultiplayer

    fun resetScore() {
        ScoreManager.reset()
        updateScoreDisplay()
    }

    fun getStats(): String = buildString {
        append("Totalt: ${ScoreManager.guessCount} gjetninger")
        append("\nRiktige: ${ScoreManager.correctCount} (${(ScoreManager.accuracy * 100).toInt()}%)")
        append("\nHøyeste streak: ${ScoreManager.highStreak}")
        append("\nPoeng totalt: ${ScoreManager.score}")
        append("\nDuplikater hoppet: $duplicateSkipCount")
    }

    fun getDuplicateCount(): Int = duplicateSkipCount

    fun resetDuplicateTracker() {
        duplicateSkipCount = 0
        playedVideoIds.clear()
    }

    companion object {
        private const val ARG_PLAYER_NAMES = "playerNames"

        fun newInstance(playerNames: List<String>? = null): VideoPlayerFragment {
            val frag = VideoPlayerFragment()
            if (!playerNames.isNullOrEmpty()) {
                val args = Bundle()
                args.putStringArrayList(ARG_PLAYER_NAMES, ArrayList(playerNames))
                frag.arguments = args
            }
            return frag
        }
    }
}