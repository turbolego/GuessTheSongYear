package com.turbolego.songguesser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
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
    private var currentVideoTitle: String = ""
    private var hasGuessedThisRound = false

    // Duplicate tracking (same session)
    private var playedVideoIds = mutableSetOf<String>()
    private var duplicateSkipCount = 0
    private var currentVideoId: String? = null

    // Permanently blocked videos (embed-disabled / error 150/152)
    private var blockedVideoIds = mutableSetOf<String>()

    // Countdown / timer
    private var countdownJob: Job? = null
    private var guessJob: Job? = null

    // YouTube Player (official IFrame API — no API key needed)
    private var youtubePlayer: YouTubePlayer? = null
    private var pendingVideoId: String? = null

    // Multiplayer
    private var isMultiplayer = false
    private var gameMode: GameMode = GameMode.CLASSIC
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
        youTubePlayerView.enableAutomaticInitialization = false
        lifecycle.addObserver(youTubePlayerView)

        // Apply referrer headers to the internal WebView — YouTube now requires
        // a valid Referer header and Referrer-Policy for embedded playback.
        // Without this, error 152-4 occurs for ALL videos in newer WebView builds.
        findWebView(youTubePlayerView)?.settings?.apply {
            userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
        }

        val playerOptions = IFramePlayerOptions.Builder(requireContext())
            .controls(1)
            .rel(0)
            .build()

        youTubePlayerView.initialize(
            object : AbstractYouTubePlayerListener() {
                override fun onReady(ytPlayer: YouTubePlayer) {
                    youtubePlayer = ytPlayer
                    if (pendingVideoId != null) {
                        ytPlayer.cueVideo(pendingVideoId!!, 0f)
                        pendingVideoId = null
                    }
                }

                override fun onError(ytPlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                    val videoId = currentVideoId

                    when (error) {
                        // Embed-disabled: silently skip to next video — don't bother the user
                        PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER -> {
                            if (videoId != null) blockedVideoIds.add(videoId)
                            loadNextVideo()
                        }

                        // Video deleted/privated: skip silently
                        PlayerConstants.PlayerError.VIDEO_NOT_FOUND -> {
                            if (videoId != null) blockedVideoIds.add(videoId)
                            loadNextVideo()
                        }

                        // REQUEST_MISSING_HTTP_REFERER, UNKNOWN, HTML5_PLAYER — may be transient,
                        // offer YouTube fallback so the user can watch externally
                        else -> {
                            if (videoId != null && videoId !in blockedVideoIds) {
                                blockedVideoIds.add(videoId)
                                showYouTubeFallback(videoId)
                            }
                        }
                    }
                }
            },
            true,
            playerOptions
        )

        // Parse arguments
        arguments?.let { args ->
            val names = args.getStringArrayList("playerNames")
            if (!names.isNullOrEmpty()) {
                playerNames = names
                isMultiplayer = true
            }
        }

        gameMode = GamePreferences.gameMode(requireContext())

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
            playVideo(retry.id, retry.year, retry.title)
            return
        }

        playVideo(entry.id, entry.year, entry.title)
    }

    private fun pickNextEntry(): VideoProvider.VideoEntry? {
        // Try to get a weighted random entry, skipping blocked (embed-disabled) and played IDs
        val entry = VideoProvider.getRandomVideoEntry(requireContext())
        if (entry != null && entry.id !in playedVideoIds && entry.id !in blockedVideoIds &&
            !PlayHistory.contains(requireContext(), entry.id)
        ) {
            return entry
        }
        if (entry != null && (entry.id in playedVideoIds || PlayHistory.contains(requireContext(), entry.id))) {
            duplicateSkipCount++
            PlayHistory.recordDuplicateCandidate(requireContext())
        }

        // If the first pick was unavailable, try more picks before reusing history.
        repeat(50) {
            val alt = VideoProvider.getRandomVideoEntry(requireContext())
            if (alt != null && alt.id !in playedVideoIds && alt.id !in blockedVideoIds &&
                !PlayHistory.contains(requireContext(), alt.id)
            ) {
                return alt
            }
            if (alt != null && (alt.id in playedVideoIds || PlayHistory.contains(requireContext(), alt.id))) {
                duplicateSkipCount++
                PlayHistory.recordDuplicateCandidate(requireContext())
            }
        }

        // A small catalog may be exhausted. Prefer a playable entry over blocking the next round.
        val any = VideoProvider.getRandomVideoEntry(requireContext())
        if (any != null && any.id !in blockedVideoIds) return any

        // All videos blocked — offer fallback
        return null
    }

    private fun playVideo(videoId: String, year: Int = 0, title: String = "") {
        currentVideoId = videoId
        currentVideoYear = year
        currentVideoTitle = title
        playedVideoIds.add(videoId)
        PlayHistory.record(requireContext(), videoId)
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
        binding.buttonYoutubeFallback.visibility = View.GONE
        binding.textViewBlockedInfo.visibility = View.GONE
        binding.numberPickerYear.visibility = View.GONE

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

        // Year picker — toggle NumberPicker visibility
        binding.buttonYearPicker.setOnClickListener {
            toggleYearPicker()
        }

        // Next video button
        binding.buttonNextVideo.setOnClickListener {
            if (isMultiplayer && gameMode == GameMode.ARCADE) {
                MultiPlayerManager.nextTurn()
            }
            loadNextVideo()
        }

        // Reveal answers (multiplayer)
        binding.buttonRevealAnswers.setOnClickListener { revealMultiplayerAnswers() }

        // YouTube fallback — open in YouTube app/browser when IFrame fails
        binding.buttonYoutubeFallback.setOnClickListener {
            val videoId = currentVideoId ?: return@setOnClickListener
            openInYoutubeApp(videoId)
        }
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

            // Auto-play the cued video after the countdown finishes
            youtubePlayer?.play()

            beginGuessTimer()
        }
    }

    private fun beginGuessTimer() {
        guessJob?.cancel()
        binding.textViewCountdown.visibility = View.GONE
        if (isMultiplayer && gameMode == GameMode.CLASSIC) {
            // In classic multiplayer, every player receives a picker in the list.
            binding.editTextGuess.visibility = View.GONE
            binding.buttonGuess.visibility = View.GONE
            binding.buttonRevealAnswers.visibility = View.VISIBLE
            return
        }
        if (isMultiplayer && gameMode == GameMode.ARCADE) {
            val player = MultiPlayerManager.getCurrentPlayer()
            binding.textViewArcadeTurn.text = getString(R.string.turn_of, player?.name ?: "?")
            binding.textViewArcadeTurn.visibility = View.VISIBLE
        }
        binding.editTextGuess.visibility = View.VISIBLE
        binding.editTextGuess.isEnabled = true
        binding.buttonGuess.visibility = View.VISIBLE
        binding.buttonYearPicker.visibility = View.VISIBLE
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
        if (isMultiplayer && gameMode == GameMode.ARCADE) {
            val player = MultiPlayerManager.getCurrentPlayer()
            if (player != null) {
                MultiPlayerManager.recordGuess(player.name, guessedYear, videoYear, currentDifficulty)
                PlayerStatistics.recordGuess(
                    requireContext(), player.name, guessedYear, videoYear, result.pointsEarned
                )
            }
        } else {
            score += result.pointsEarned
            streak = if (result.pointsEarned > 0) streak + 1 else 0
            PlayerStatistics.recordGuess(
                requireContext(), getString(R.string.app_name), guessedYear, videoYear, result.pointsEarned
            )
        }

        showAnswer(videoYear, guessedYear)
    }

    private fun showAnswer(actualYear: Int, guessedYear: Int) {
        binding.textViewSongTitle.text = currentVideoTitle.ifEmpty { "???" }
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
        } else if (gameMode == GameMode.ARCADE) {
            binding.buttonRevealAnswers.visibility = View.GONE
            binding.buttonNextVideo.visibility = View.VISIBLE
            val player = MultiPlayerManager.getCurrentPlayer()
            if (player != null) {
                binding.textViewArcadeTurn.text = getString(R.string.arcade_score_summary, player.name, player.score)
                binding.textViewArcadeTurn.visibility = View.VISIBLE
            }
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
    // YEAR PICKER (NumberPicker) — replaces EditText typing with a scroll wheel
    // ═══════════════════════════════════════════════════════════════════════════

    private var isYearPickerSetup = false

    private fun setupYearPicker() {
        if (isYearPickerSetup) return
        isYearPickerSetup = true

        binding.numberPickerYear.apply {
            minValue = YEAR_MIN
            maxValue = YEAR_MAX
            value = YEAR_MAX // default to latest year
            wrapSelectorWheel = false

            setTextColor(android.graphics.Color.parseColor("#e6edf3"))
            setTextSize(22f)
        }

        binding.numberPickerYear.setOnValueChangedListener { _, _oldVal, newVal ->
            binding.editTextGuess.setText(newVal.toString())
        }
    }

    private fun toggleYearPicker() {
        if (!isYearPickerSetup) setupYearPicker()

        val isVisible = binding.numberPickerYear.visibility == View.VISIBLE
        if (isVisible) {
            // Hide picker, show edit text + guess button
            binding.numberPickerYear.visibility = View.GONE
            binding.editTextGuess.visibility = View.VISIBLE
            binding.buttonGuess.visibility = View.VISIBLE
        } else {
            // Show picker, hide edit text + guess button
            // Sync picker to current edit text value
            val currentText = binding.editTextGuess.text.toString()
            val currentYear = currentText.toIntOrNull()
            if (currentYear != null && currentYear in YEAR_MIN..YEAR_MAX) {
                binding.numberPickerYear.value = currentYear
            }
            binding.numberPickerYear.visibility = View.VISIBLE
            binding.editTextGuess.visibility = View.GONE
            binding.buttonGuess.visibility = View.VISIBLE // keep guess button
        }
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

    fun getDuplicateCount(): Int = PlayHistory.duplicateCount(requireContext())
    fun resetDuplicateTracker() {
        duplicateSkipCount = 0
        PlayHistory.clear(requireContext())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MULTIPLAYER
    // ═══════════════════════════════════════════════════════════════════════════

    private fun setupMultiplayerUI() {
        binding.textViewScore.visibility = View.GONE
        binding.buttonYearPicker.visibility = View.GONE
        binding.numberPickerYear.visibility = View.GONE

        if (gameMode == GameMode.ARCADE) {
            binding.recyclerViewPlayers.visibility = View.GONE
            binding.textViewArcadeTurn.text = getString(R.string.turn_of, MultiPlayerManager.getCurrentPlayerName())
            binding.textViewArcadeTurn.visibility = View.VISIBLE
        } else {
            binding.editTextGuess.visibility = View.GONE
            binding.buttonGuess.visibility = View.GONE
            multiplayerAdapter = MultiplayerGuessAdapter(playerNames)
            binding.recyclerViewPlayers.apply {
                layoutManager = LinearLayoutManager(requireContext())
                adapter = multiplayerAdapter
                visibility = View.VISIBLE
            }
        }
    }

    fun revealMultiplayerAnswers() {
        val values = multiplayerAdapter?.getCurrentPickerValues() ?: return
        val videoYear = currentVideoYear
        for ((index, playerName) in playerNames.withIndex()) {
            val guessedYear = values.firstOrNull { it.first == playerName }?.second ?: 1992
            MultiPlayerManager.recordGuess(playerName, guessedYear, videoYear, currentDifficulty)
            val player = MultiPlayerManager.allPlayers.find { it.name == playerName }
            player?.guessResult?.let { result ->
                PlayerStatistics.recordGuess(
                    requireContext(), playerName, guessedYear, videoYear, result.pointsEarned
                )
            }
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

    // ═══════════════════════════════════════════════════════════════════════════
    // YOUTUBE FALLBACK — when IFrame API can't play a video (embed disabled)
    // ═══════════════════════════════════════════════════════════════════════════

    fun showYouTubeFallback(videoId: String) {
        // Cancel timers — the video failed to play
        countdownJob?.cancel()
        guessJob?.cancel()

        requireActivity().runOnUiThread {
            binding.editTextGuess.isEnabled = false
            binding.buttonGuess.isEnabled = false
            binding.textViewSongTitle.text = "???"
            binding.textViewArtist.text = "⚠ " + getString(R.string.video_not_available)
            binding.textViewCountdown.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
            binding.textViewFeedback.text = getString(R.string.video_embed_blocked)
            binding.textViewFeedback.visibility = View.VISIBLE
            binding.buttonYoutubeFallback.visibility = View.VISIBLE
            binding.textViewBlockedInfo.visibility = View.VISIBLE

            // Keep the guess cycle working — user can watch on YouTube then guess
            beginGuessTimer()
        }
    }

    private fun openInYoutubeApp(videoId: String) {
        try {
            // Try official YouTube app first
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/watch?v=$videoId")).apply {
                setPackage("com.google.android.youtube")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
        } catch (_: Exception) {
            // YouTube app not installed — fall back to browser
            try {
                val intent = Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/watch?v=$videoId")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(requireContext(),
                    getString(R.string.error_open_youtube), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Recursively find the WebView inside the YouTubePlayerView hierarchy.
     * The library's view tree is: YouTubePlayerView → SixteenByNineFrameLayout →
     * LegacyYouTubePlayerView → WebView. We need the WebView to set HTTP headers
     * (Referer, Referrer-Policy) required by YouTube's new embed security policy.
     */
    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findWebView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }
}
