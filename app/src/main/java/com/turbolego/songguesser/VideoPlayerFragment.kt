package com.turbolego.songguesser

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.NumberPicker
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.turbolego.songguesser.databinding.FragmentVideoPlayerBinding
import com.turbolego.songguesser.databinding.ItemPlayerGuessBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

private const val TAG = "VideoPlayerFragment"
private const val MAX_RETRY_ATTEMPTS = 5
private const val YEAR_MIN = 1960
private const val YEAR_MAX = 2025

data class KnownVideo(val id: String, val year: Int)
data class ApiVideo(val id: String, val year: Int, val views: Long, val title: String)

private val fallbackVideoList = listOf(
    KnownVideo("dQw4w9WgXcQ", 1987), KnownVideo("ZbZSe6N_BXs", 1985),
    KnownVideo("djV1KL4Btzw", 1984), KnownVideo("rYEDA3JiTEA", 1984),
    KnownVideo("1w7OgIMMRc4", 1985), KnownVideo("1G4isv_Fyls", 1985),
    KnownVideo("hr0ObGAUDlQ", 1982), KnownVideo("hcwnL9a61o0", 1999),
    KnownVideo("tF3iR0rN-8g", 1991), KnownVideo("q3zKKtYsEj8", 1994),
    KnownVideo("ZmDBbnMqK70", 1999), KnownVideo("YR5W3FKE88Q", 1998),
    KnownVideo("XbGsChTe4go", 1999), KnownVideo("6KnRLJZ0ZRw", 1995),
    KnownVideo("eBCRc2Zk6hA", 2000), KnownVideo("dQ1ribkayAU", 2008),
    KnownVideo("lp-EO5I60KA", 2009), KnownVideo("kJQP7kiF5Fk", 2006),
    KnownVideo("9bZkp7q19f0", 2012), KnownVideo("YQHsXMglC9A", 2015),
    KnownVideo("OPf0YbXqDm0", 2014), KnownVideo("2Vv-BfVoq4g", 2017),
    KnownVideo("kPa7bsDwL-c", 2023), KnownVideo("hVlgHmeZjg8", 2021),
    KnownVideo("W0DW0WCb5ac", 2023), KnownVideo("iWzvlFnyYwE", 2023),
)

private var currentVideoList: MutableList<ApiVideo> = mutableListOf()
val playedVideoIds: MutableSet<String> = mutableSetOf()
var duplicateSkipCount = 0

private suspend fun fetchVideosFromApi() {
    Log.d(TAG, "Fetching videos from InnerTube API...")
    val apiVideos = try { YouTubeSearchService.searchMusicVideos() } catch (e: Exception) { emptyList() }
    if (apiVideos.isNotEmpty()) {
        Log.d(TAG, "Got ${apiVideos.size} from API")
        currentVideoList.clear(); currentVideoList.addAll(apiVideos)
    } else {
        Log.w(TAG, "Using fallback (${fallbackVideoList.size} videos)")
        currentVideoList.clear(); currentVideoList.addAll(fallbackVideoList.map { ApiVideo(it.id, it.year, 0, "") })
    }
}

private fun pickCandidate(difficulty: Difficulty): ApiVideo? {
    val pool = currentVideoList.filter { it.year in difficulty.yearRangeStart..difficulty.yearRangeEnd }
    if (pool.isEmpty()) return null
    val unplayed = pool.filter { it.id !in playedVideoIds }
    if (unplayed.isEmpty()) {
        Log.d(TAG, "All ${pool.size} videos played — resetting session")
        playedVideoIds.clear(); duplicateSkipCount = 0
        return pool.random()
    }
    return unplayed.random()
}

// ── Multiplayer guess row data ──────────────────────────────────────────

data class PlayerGuessRow(
    val playerName: String,
    var selectedYear: Int = 2000,
    var hasGuessed: Boolean = false,
    var resultText: String? = null,
    var resultColor: Int = 0,
)

/** Adapter that shows each player's name + NumberPicker (no individual guess button). */
class PlayerGuessAdapter(
    val rows: MutableList<PlayerGuessRow>,
) : RecyclerView.Adapter<PlayerGuessAdapter.VH>() {

    class VH(val binding: ItemPlayerGuessBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemPlayerGuessBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        val b = holder.binding

        b.textViewPlayerName.text = row.playerName

        // Configure NumberPicker
        val picker = b.numberPickerYear
        picker.minValue = YEAR_MIN
        picker.maxValue = YEAR_MAX
        picker.value = row.selectedYear.coerceIn(YEAR_MIN, YEAR_MAX)
        picker.setWrapSelectorWheel(false)
        picker.setOnValueChangedListener { _, _, newVal -> rows[position].selectedYear = newVal }

        if (row.hasGuessed) {
            picker.isEnabled = false
            if (row.resultText != null) {
                b.textViewPlayerResult.text = row.resultText
                b.textViewPlayerResult.visibility = View.VISIBLE
                b.textViewPlayerResult.setTextColor(row.resultColor)
            }
        } else {
            picker.isEnabled = true
            b.textViewPlayerResult.visibility = View.GONE
        }
    }

    /** Disable all pickers and show each player's result. */
    fun revealAll(results: List<Pair<String, ResultDisplay>>) {
        for (i in rows.indices) {
            rows[i].hasGuessed = true
            val match = results.find { it.first == rows[i].playerName }
            if (match != null) {
                rows[i].resultText = match.second.text
                rows[i].resultColor = match.second.color
            }
        }
        notifyDataSetChanged()
    }

    fun resetAll() {
        rows.forEach { it.hasGuessed = false; it.resultText = null; it.selectedYear = 2000 }
        notifyDataSetChanged()
    }
}

data class ResultDisplay(val text: String, val color: Int)

// ═══════════════════════════════════════════════════════════════════════════════
// FRAGMENT
// ═══════════════════════════════════════════════════════════════════════════════

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

    // ── Multiplayer state (simultaneous guessing) ────────────────────────────
    private var isMultiplayer = false
    private var mpPlayerNames: List<String> = emptyList()
    private var mpRevealed = false  // true once "Vis svar" has been pressed for this round
    private var playerAdapter: PlayerGuessAdapter? = null

    /** Whether the "Vis svar" button should be shown (host = true, client = false). */
    private var showReveal: Boolean = true

    /** True if this device is hosting a network game. */
    private val isNetworkHost: Boolean get() = HostGameService.instance != null

    /** True if this device is a remote client in a network game. */
    private val isNetworkClient: Boolean get() = JoinGameService.instance != null && !isNetworkHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { args ->
            val names = args.getStringArrayList(ARG_PLAYER_NAMES)
            if (!names.isNullOrEmpty()) {
                isMultiplayer = true
                mpPlayerNames = names
                MultiPlayerManager.clear()
                names.forEach { if (it.isNotBlank()) MultiPlayerManager.addPlayer(it) }
            }
            showReveal = args.getBoolean(ARG_SHOW_REVEAL, true)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentVideoPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupListeners()
        setupYouTubePlayer()

        if (isMultiplayer) setupMultiplayerUI()

        if (isNetworkClient) {
            // Client mode: register listener, no API fetching (video from host)
            setupClientNetworkListener()
        } else {
            lifecycleScope.launch { fetchVideosFromApi() }
        }

        if (!isMultiplayer) updateScoreDisplay()
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

    // ═══════════════════════════════════════════════════════════════════════════
    // LISTENERS
    // ═══════════════════════════════════════════════════════════════════════════

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

        binding.buttonNextVideo.setOnClickListener { loadNextVideo() }

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

        // Multiplayer "Vis svar" button
        binding.buttonRevealAnswers.setOnClickListener { revealAnswers() }
    }

    private fun setupYouTubePlayer() {
        lifecycle.addObserver(binding.youtubePlayerView)

        val options = IFramePlayerOptions.Builder(requireContext())
            .controls(1).fullscreen(1).build()

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
                    enableGuessing()
                    if (currentDifficulty.hintEnabled && !isMultiplayer) showHint()
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
                        try { youTubePlayer?.loadVideo(candidate.id, 0f) } catch (_: Exception) {}
                    } else {
                        Toast.makeText(requireContext(), R.string.error_loading_video, Toast.LENGTH_LONG).show()
                        binding.buttonNextVideo.visibility = View.VISIBLE
                        binding.progressBar.visibility = View.GONE
                    }
                }
            }
        }, options)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MULTIPLAYER
    // ═══════════════════════════════════════════════════════════════════════════

    private fun setupMultiplayerUI() {
        // Hide single-player guess UI
        binding.editTextGuess.visibility = View.GONE
        binding.buttonGuess.visibility = View.GONE

        // Show video toggle + leaderboard
        binding.buttonToggleVideo.visibility = View.VISIBLE
        binding.textViewLeaderboard.visibility = View.VISIBLE

        // Show reveal button (only for host)
        binding.buttonRevealAnswers.visibility = if (showReveal) View.VISIBLE else View.GONE

        // Build RecyclerView with NumberPickers
        val rows = mpPlayerNames.map { PlayerGuessRow(playerName = it) }.toMutableList()
        playerAdapter = PlayerGuessAdapter(rows)

        binding.recyclerViewPlayers.apply {
            visibility = View.VISIBLE
            layoutManager = LinearLayoutManager(requireContext())
            adapter = playerAdapter
        }

        binding.textViewScore.text = "Flerspiller"
        refreshLeaderboard()
    }

    /** Called when "Vis svar" is pressed — locks all pickers and shows results. */
    private fun revealAnswers() {
        if (mpRevealed || currentVideo == null) return
        mpRevealed = true

        val video = currentVideo
        val adapter = playerAdapter ?: return

        // Collect host's local guesses
        val localGuesses = mutableMapOf<String, Int>()
        for (row in adapter.rows) {
            localGuesses[row.playerName] = row.selectedYear
        }

        // If hosting a network game, sync with remote clients
        if (isNetworkHost) {
            lifecycleScope.launch {
                HostGameService.instance?.triggerReveal(localGuesses)
                // After triggerReveal returns, results are stored in GameSessionManager
                // Update local display with all results including remote players
                computeAndDisplayResults()
            }
        } else {
            computeAndDisplayResults()
        }
    }

    private fun computeAndDisplayResults() {
        val video = currentVideo ?: return
        val adapter = playerAdapter ?: return
        val results = mutableListOf<Pair<String, ResultDisplay>>()

        for ((i, row) in adapter.rows.withIndex()) {
            val name = row.playerName
            val guessYear = row.selectedYear

            MultiPlayerManager.recordGuess(name, guessYear, video!!.year, currentDifficulty)
            val player = MultiPlayerManager.allPlayers.find { it.name == name }
            val result = player?.guessResult ?: ScoreManager.evaluateGuess(guessYear, video.year, currentDifficulty)

            val isCorrect = result.isCorrect
            val points = result.pointsEarned
            val color = ResourcesCompat.getColor(
                resources, if (isCorrect) R.color.green_correct else R.color.red_wrong, null
            )
            val text = buildString {
                append(getString(result.messageResId, *result.messageArgs.toTypedArray()))
                if (points > 0) append("\n+${points} poeng")
            }
            results.add(name to ResultDisplay(text, color))
        }

        adapter.revealAll(results)
        refreshLeaderboard()

        // Show "Neste video" button with year
        binding.buttonRevealAnswers.visibility = View.GONE
        binding.buttonNextVideo.text = "${video!!.year} — ${getString(R.string.next_video)}"
        binding.buttonNextVideo.visibility = View.VISIBLE
        binding.textViewSongYear.visibility = View.GONE
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NETWORK CLIENT MODE
    // ═══════════════════════════════════════════════════════════════════════════

    private fun setupClientNetworkListener() {
        val joinService = JoinGameService.instance ?: return
        joinService.networkListener = object : GameNetworkListener {
            override fun onHostingStarted(sessionId: String, hostName: String) {}
            override fun onServiceRegistered(serviceName: String) {}
            override fun onJoinedSession(session: GameSessionManager.GameSession) {}
            override fun onPlayerJoined(playerName: String, clientIp: String) {}
            override fun onPlayerDisconnected(playerName: String) {}
            override fun onTurnReceived(playerName: String) {}
            override fun onGuessReceived(playerName: String, guess: Int, correctYear: Int, score: Int) {}
            override fun onSessionEnded() {
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "Spillet er slutt", Toast.LENGTH_LONG).show()
                }
            }
            override fun onNetworkError(error: String) {
                Log.w(TAG, "Network error (client): $error")
            }

            override fun onVideoReceived(videoId: String, year: Int, title: String) {
                Log.d(TAG, "Client received VIDEO: $videoId ($year - $title)")
                requireActivity().runOnUiThread {
                    // Create a candidate from the host's broadcast
                    val candidate = ApiVideo(id = videoId, year = year, views = 0L, title = title)
                    currentVideo = candidate
                    playedVideoIds.add(videoId)
                    if (isPlayerReady) {
                        beginCountdown(videoId)
                    }
                }
            }

            override fun onRevealReceived() {
                Log.d(TAG, "Client received REVEAL — sending blind guess")
                val adapter = playerAdapter
                if (adapter != null && adapter.rows.isNotEmpty()) {
                    val myGuess = adapter.rows[0].selectedYear
                    lifecycleScope.launch {
                        joinService.sendGuessBlind(myGuess)
                    }
                }
            }

            override fun onRevealResultReceived(results: List<GameSessionManager.RevealResult>) {
                Log.d(TAG, "Client received ${results.size} reveal results")
                requireActivity().runOnUiThread {
                    displayNetworkResults(results)
                }
            }
        }
    }

    private fun displayNetworkResults(results: List<GameSessionManager.RevealResult>) {
        val adapter = playerAdapter ?: return
        val displayResults = results.map {
            val color = ResourcesCompat.getColor(
                resources, if (it.isCorrect) R.color.green_correct else R.color.red_wrong, null
            )
            val text = if (it.isCorrect) {
                "Riktig! ±${it.difference} år\n+${it.pointsEarned} poeng"
            } else {
                "Feil: ${it.correctYear} (±${it.difference} år)\n+${it.pointsEarned} poeng"
            }
            it.playerName to ResultDisplay(text, color)
        }
        adapter.revealAll(displayResults)
        refreshLeaderboard()

        binding.buttonNextVideo.text = "${results.firstOrNull()?.correctYear ?: ""} — ${getString(R.string.next_video)}"
        binding.buttonNextVideo.visibility = View.VISIBLE
    }

    private fun refreshLeaderboard() {
        val text = buildString {
            MultiPlayerManager.getLeaderboard().forEachIndexed { i, p ->
                when (i) {
                    0 -> append("🥇 ")
                    1 -> append("🥈 ")
                    2 -> append("🥉 ")
                    else -> append("${i + 1}. ")
                }
                append("${p.name} — ${p.score} poeng\n")
            }
        }
        binding.textViewLeaderboard.text = text
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HINTS (single-player only)
    // ═══════════════════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════════════════
    // GAME FLOW
    // ═══════════════════════════════════════════════════════════════════════════

    private fun submitGuess() {
        // Single-player only
        if (isMultiplayer || hasGuessedThisRound) return
        val guessText = binding.editTextGuess.text.toString().trim()
        if (guessText.length != 4) {
            Toast.makeText(requireContext(), R.string.error_invalid_year, Toast.LENGTH_SHORT).show()
            return
        }
        val guessedYear = guessText.toIntOrNull()
        if (guessedYear == null || guessedYear !in YEAR_MIN..YEAR_MAX) {
            Toast.makeText(requireContext(), R.string.error_invalid_year, Toast.LENGTH_SHORT).show()
            return
        }

        val video = currentVideo ?: return
        hasGuessedThisRound = true
        binding.editTextGuess.isEnabled = false
        binding.buttonGuess.isEnabled = false

        val result = ScoreManager.evaluateGuess(guessedYear, video.year, currentDifficulty)

        val fbText = if (result.pointsEarned > 0) {
            "${getString(result.messageResId, *result.messageArgs.toTypedArray())}\n${getString(R.string.score_earned, result.pointsEarned)}"
        } else {
            getString(result.messageResId, *result.messageArgs.toTypedArray())
        }
        binding.textViewFeedback.text = fbText
        binding.textViewFeedback.setTextColor(
            ResourcesCompat.getColor(
                resources, if (result.isCorrect) R.color.green_correct else R.color.red_wrong, null
            )
        )
        binding.textViewFeedback.visibility = View.VISIBLE

        binding.textViewSongYear.text = getString(R.string.song_release_year, video.year)
        binding.textViewSongYear.visibility = View.GONE

        binding.buttonNextVideo.text = "${video.year} — ${getString(R.string.next_video)}"
        binding.buttonNextVideo.visibility = View.VISIBLE
        updateScoreDisplay()
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

                // Reset multiplayer state
                if (isMultiplayer) {
                    mpRevealed = false
                    playerAdapter?.resetAll()
                    binding.buttonRevealAnswers.visibility = if (showReveal) View.VISIBLE else View.GONE
                    binding.buttonNextVideo.visibility = View.GONE
                    refreshLeaderboard()
                }

                // Broadcast video info to remote clients
                if (isNetworkHost) {
                    HostGameService.instance?.broadcastVideo(candidate.id, candidate.year, candidate.title)
                }

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
    }

    private fun enableGuessing() {
        if (isMultiplayer) return  // NumberPickers are always enabled until reveal
        binding.editTextGuess.isEnabled = true
        binding.editTextGuess.requestFocus()
    }

    private fun updateScoreDisplay() {
        if (isMultiplayer) {
            binding.textViewScore.text = "Flerspiller"
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
        private const val ARG_SHOW_REVEAL = "SHOW_REVEAL"

        fun newInstance(playerNames: List<String>? = null, showReveal: Boolean = true): VideoPlayerFragment {
            val frag = VideoPlayerFragment()
            val args = Bundle()
            if (!playerNames.isNullOrEmpty()) {
                args.putStringArrayList(ARG_PLAYER_NAMES, ArrayList(playerNames))
            }
            args.putBoolean(ARG_SHOW_REVEAL, showReveal)
            frag.arguments = args
            return frag
        }
    }
}