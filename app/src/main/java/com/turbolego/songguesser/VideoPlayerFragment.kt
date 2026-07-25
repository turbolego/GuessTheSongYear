package com.turbolego.songguesser

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import com.turbolego.songguesser.databinding.FragmentVideoPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.random.Random

class VideoPlayerFragment : Fragment() {

    // ── Binding ───────────────────────────────────────────────────────────

    private var _binding: FragmentVideoPlayerBinding? = null
    private val binding get() = _binding!!

    // ── Game state ─────────────────────────────────────────────────────────

    private var currentDifficulty: Difficulty = Difficulty.MEDIUM
    private var score = 0
    private var streak = 0
    private var currentVideoYear: Int = 0
    private var hasGuessedThisRound = false

    // Duplicate tracking (same session)
    private var playedVideoIds = mutableSetOf<String>()
    private var duplicateSkipCount = 0
    private var currentVideoId: String? = null

    // Countdown / timer
    private var countdownJob: Job? = null
    private var guessJob: Job? = null

    // YouTube Player (official IFrame API — no API key needed)
    private var youtubePlayer: YouTubePlayer? = null
    private var pendingVideoId: String? = null

    // Multiplayer
    private var isMultiplayer = false
    var playerNames: List<String> = emptyList() // internal for JoinGameFragment callback
    @JvmField var multiplayerAdapter: MultiplayerGuessAdapter? = null
    private var isNetworkClient = false

    // ── Factory ────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "VideoPlayer"
        const val YEAR_MIN = 1960
        const val YEAR_MAX = 2025

        @Volatile var activeFragment: VideoPlayerFragment? = null

        fun newInstance(playerNames: List<String>): VideoPlayerFragment {
            val frag = VideoPlayerFragment()
            frag.arguments = Bundle().apply {
                putStringArrayList("playerNames", ArrayList(playerNames))
            }
            return frag
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVideoPlayerBinding.inflate(inflater, container, false)
        activeFragment = this
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Load video database from assets (zero API keys)
        context?.let { VideoProvider.load(it) }

        // Set up YouTube Player (official IFrame API — fully Play Store compliant)
        val youTubePlayerView: YouTubePlayerView = binding.youtubePlayerView
        lifecycle.addObserver(youTubePlayerView)

        youTubePlayerView.addYouTubePlayerListener(object : AbstractYouTubePlayerListener() {
            override fun onReady(ytPlayer: YouTubePlayer) {
                youtubePlayer = ytPlayer
                if (pendingVideoId != null) {
                    ytPlayer.cueVideo(pendingVideoId!!, 0f)
                    pendingVideoId = null
                }
            }
        })

        // Parse arguments
        arguments?.let { args ->
            val names = args.getStringArrayList("playerNames")
            if (!names.isNullOrEmpty()) {
                playerNames = names
                isMultiplayer = true
            }
        }

        setupListeners()
        if (isMultiplayer) setupMultiplayerUI()
        if (!isMultiplayer) updateScoreDisplay()

        // Don't load a video if this is a network client — the host sends the video
        if (!isNetworkClient) {
            // Start the game
            loadNextVideo()
        }
    }

    override fun onResume() {
        super.onResume()
        // YouTubePlayerView lifecycle managed by lifecycle.addObserver — do not force play()
    }

    override fun onPause() {
        super.onPause()
        // Do NOT call pause() — IFrame Player API handles background policy automatically
        // This is required for Play Store compliance (no background audio)
    }

    override fun onDestroyView() {
        countdownJob?.cancel()
        guessJob?.cancel()
        _binding = null
        super.onDestroyView()
        activeFragment = null
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VIDEO LOADING — from VideoProvider (zero-API-key asset file)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun loadNextVideo() {
        countdownJob?.cancel()
        guessJob?.cancel()

        // Pick the next video (avoiding duplicates in this session)
        val entry = pickNextEntry() ?: run {
            // All videos played — reset the pool
            playedVideoIds.clear()
            val retry = pickNextEntry()
            if (retry == null) {
                Toast.makeText(requireContext(), R.string.error_no_videos_left, Toast.LENGTH_SHORT).show()
                return
            }
            playVideo(retry.id, retry.year)
            return
        }

        playVideo(entry.id, entry.year)
    }

    private fun pickNextEntry(): VideoProvider.VideoEntry? {
        // Try to get a weighted random entry
        val entry = VideoProvider.getRandomVideoEntryWeighted()
        if (entry != null && entry.id !in playedVideoIds) {
            return entry
        }

        // If weighted pick was a duplicate, try more picks
        repeat(50) {
            val alt = VideoProvider.getRandomVideoEntryWeighted()
            if (alt != null && alt.id !in playedVideoIds) {
                return alt
            }
        }

        // Fallback: pick any from the full list
        val any = VideoProvider.getRandomVideoEntryWeighted()
        return any
    }

    private fun playVideo(videoId: String, year: Int = 0) {
        currentVideoId = videoId
        currentVideoYear = year
        playedVideoIds.add(videoId)
        hasGuessedThisRound = false

        // Reset UI
        binding.editTextGuess.text.clear()
        binding.editTextGuess.isEnabled = false
        binding.buttonGuess.isEnabled = false
        binding.textViewFeedback.visibility = View.GONE
        binding.buttonNextVideo.visibility = View.GONE
        binding.textViewHint.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.textViewSongTitle.text = "???"
        binding.textViewArtist.text = "???"
        binding.textViewCountdown.visibility = View.GONE

        // Reset multiplayer adapter for new round
        multiplayerAdapter?.resetForNewRound()

        // If we're hosting a network game, broadcast the video to clients
        if (isMultiplayer && HostGameService.instance != null) {
            HostGameService.instance?.broadcastVideo(videoId, year, "???")
        }

        // Queue video in YouTube Player (official IFrame API)
        if (youtubePlayer != null) {
            youtubePlayer?.cueVideo(videoId, 0f)
        } else {
            pendingVideoId = videoId
        }

        // Start countdown
        beginCountdown()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UI SETUP
    // ═══════════════════════════════════════════════════════════════════════════

    private fun setupListeners() {
        // Guess input
        binding.editTextGuess.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.buttonGuess.isEnabled = !s.isNullOrBlank()
            }
        })
        binding.editTextGuess.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitGuess()
                true
            } else false
        }
        binding.buttonGuess.setOnClickListener { submitGuess() }

        // Next video button
        binding.buttonNextVideo.setOnClickListener { loadNextVideo() }

        // Reveal answers (multiplayer)
        binding.buttonRevealAnswers.setOnClickListener { revealMultiplayerAnswers() }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COUNTDOWN & GUESS TIMER
    // ═══════════════════════════════════════════════════════════════════════════

    private fun beginCountdown() {
        val countdownSteps = listOf(3, 2, 1)
        countdownJob = lifecycleScope.launch {
            for (step in countdownSteps) {
                binding.textViewCountdown.text = step.toString()
                binding.textViewCountdown.visibility = View.VISIBLE
                delay(1_000L)
            }
            binding.textViewCountdown.visibility = View.GONE
            beginGuessTimer()
        }
    }

    private fun beginGuessTimer() {
        guessJob?.cancel()
        binding.textViewCountdown.visibility = View.GONE
        if (isMultiplayer) {
            // In multiplayer, each player uses their own number picker via the adapter
            binding.editTextGuess.visibility = View.GONE
            binding.buttonGuess.visibility = View.GONE
            return
        }
        binding.editTextGuess.isEnabled = true
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GUESS LOGIC
    // ═══════════════════════════════════════════════════════════════════════════

    private fun submitGuess() {
        if (hasGuessedThisRound) return
        val videoYear = currentVideoYear

        guessJob?.cancel()
        binding.textViewCountdown.visibility = View.GONE

        val input = binding.editTextGuess.text.toString()
        if (input.isBlank()) {
            showAnswer(videoYear, Int.MAX_VALUE)
            return
        }

        val guessedYear = input.toIntOrNull() ?: run {
            Toast.makeText(requireContext(), R.string.error_invalid_year, Toast.LENGTH_SHORT).show()
            beginGuessTimer()
            return
        }

        if (guessedYear < YEAR_MIN || guessedYear > YEAR_MAX) {
            Toast.makeText(requireContext(), R.string.error_invalid_year, Toast.LENGTH_SHORT).show()
            beginGuessTimer()
            return
        }

        hasGuessedThisRound = true

        val result = ScoreManager.evaluateGuess(guessedYear, videoYear, currentDifficulty)
        score += result.pointsEarned
        streak = if (result.pointsEarned > 0) streak + 1 else 0

        showAnswer(videoYear, guessedYear)
    }

    private fun showAnswer(actualYear: Int, guessedYear: Int) {
        binding.textViewSongTitle.text = "???"
        binding.textViewArtist.text = actualYear.toString()
        binding.editTextGuess.isEnabled = false
        binding.buttonGuess.isEnabled = false

        val diff = abs(guessedYear - actualYear)
        val msg = if (guessedYear == Int.MAX_VALUE) {
            getString(R.string.wrong, actualYear)
        } else {
            when {
                diff == 0 -> getString(R.string.correct_exact)
                diff <= 2 -> getString(R.string.correct_very_close, diff)
                diff <= 5 -> getString(R.string.correct_close, diff)
                diff <= 15 -> getString(R.string.correct_ok, diff)
                else -> getString(R.string.wrong, actualYear)
            }
        }
        binding.textViewFeedback.text = msg
        binding.textViewFeedback.visibility = View.VISIBLE

        if (!isMultiplayer) {
            updateScoreDisplay()
            binding.buttonNextVideo.visibility = View.VISIBLE
        } else {
            binding.buttonRevealAnswers.visibility = View.VISIBLE
        }

        showHint(actualYear, guessedYear)
    }

    private fun showHint(actualYear: Int, guessedYear: Int) {
        val decade = (actualYear / 10) * 10
        binding.textViewHint.text = getString(R.string.hint_decade, decade)
        binding.textViewHint.visibility = View.VISIBLE
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SCORING
    // ═══════════════════════════════════════════════════════════════════════════

    private fun updateScoreDisplay() {
        binding.textViewScore.text = getString(R.string.score_label, score)
    }

    fun setDifficulty(difficulty: Difficulty) {
        currentDifficulty = difficulty
    }

    fun getStats(): String =
        "Poeng: $score | Streak: $streak | Spilt: ${playedVideoIds.size}"

    fun resetScore() {
        score = 0
        streak = 0
        playedVideoIds.clear()
        duplicateSkipCount = 0
        updateScoreDisplay()
    }

    fun getDuplicateCount(): Int = duplicateSkipCount
    fun resetDuplicateTracker() { duplicateSkipCount = 0 }

    // ═══════════════════════════════════════════════════════════════════════════
    // MULTIPLAYER
    // ═══════════════════════════════════════════════════════════════════════════

    private fun setupMultiplayerUI() {
        binding.editTextGuess.visibility = View.GONE
        binding.buttonGuess.visibility = View.GONE
        binding.textViewScore.visibility = View.GONE

        multiplayerAdapter = MultiplayerGuessAdapter(playerNames)
        binding.recyclerViewPlayers.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = multiplayerAdapter
            visibility = View.VISIBLE
        }
    }

    fun revealMultiplayerAnswers() {
        val values = multiplayerAdapter?.getCurrentPickerValues() ?: return
        val videoYear = currentVideoYear
        for ((index, playerName) in playerNames.withIndex()) {
            val guessedYear = values.firstOrNull { it.first == playerName }?.second ?: 1992
            MultiPlayerManager.recordGuess(playerName, guessedYear, videoYear, currentDifficulty)
            val player = MultiPlayerManager.allPlayers.find { it.name == playerName }
            val resultText = player?.guessResult?.let { r ->
                getString(R.string.score_earned, r.pointsEarned)
            } ?: ""
            multiplayerAdapter?.setPlayerResult(index, resultText)
        }
        multiplayerAdapter?.revealAnswers()
        binding.buttonRevealAnswers.visibility = View.GONE
        binding.buttonNextVideo.visibility = View.VISIBLE
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NETWORK CLIENT SUPPORT
    // ═══════════════════════════════════════════════════════════════════════════

    fun setAsNetworkClient() {
        isNetworkClient = true
    }

    fun receiveVideoFromHost(videoId: String, year: Int) {
        currentVideoYear = year
        if (youtubePlayer != null) {
            youtubePlayer?.cueVideo(videoId, 0f)
        } else {
            pendingVideoId = videoId
        }
        binding.textViewSongTitle.text = "???"
        binding.textViewArtist.text = getString(R.string.year_unknown)
        binding.progressBar.visibility = View.GONE
        beginCountdown()
    }
}
