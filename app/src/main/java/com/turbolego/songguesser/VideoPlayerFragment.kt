package com.turbolego.songguesser

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
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
import com.turbolego.songguesser.databinding.FragmentVideoPlayerBinding
import com.turbolego.songguesser.databinding.ItemPlayerGuessBinding
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.seconds
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "VideoPlayerFragment"
private const val YEAR_MIN = 1960
private const val YEAR_MAX = 2025
private const val ENABLE_DEBUG_LOGS = false

data class KnownVideo(val id: String, val year: Int)
data class ApiVideo(val id: String, val year: Int, val views: Long, val title: String)

/** Video metadata fetched from YouTube oEmbed API (no key required). */
data class VideoMetadata(
    val title: String,
    val authorName: String,
    val thumbnailUrl: String
)

private val fallbackVideoList = listOf(
    KnownVideo("dQw4w9WgXcQ", 1987),     // Rick Astley — Never Gonna Give You Up
    KnownVideo("ZbZSe6N_BXs", 2013),     // Pharrell Williams — Happy
    KnownVideo("1w7OgIMMRc4", 1987),     // Guns N' Roses — Sweet Child O' Mine
    KnownVideo("lp-EO5I60KA", 2014),     // Ed Sheeran — Thinking Out Loud
    KnownVideo("9bZkp7q19f0", 2012),     // PSY — Gangnam Style
    KnownVideo("YQHsXMglC9A", 2015),     // Adele — Hello
    KnownVideo("OPf0YbXqDm0", 2014),     // Mark Ronson — Uptown Funk
    KnownVideo("2Vv-BfVoq4g", 2017),     // Ed Sheeran — Perfect
    KnownVideo("fLexgOxsZu0", 2010),     // Bruno Mars — The Lazy Song
    KnownVideo("kJQP7kiw5Fk", 2017),     // Luis Fonsi — Despacito
    KnownVideo("YlUKcNNmywk", 1999),     // Red Hot Chili Peppers — Californication
    KnownVideo("QcIy9NiNbmo", 2014),     // Taylor Swift — Bad Blood
    KnownVideo("fRh_vgS2dFE", 2015),     // Justin Bieber — Sorry
    KnownVideo("JGwWNGJdvx8", 2017),     // Ed Sheeran — Shape of You
    KnownVideo("RgKAFK5djSk", 2015),     // Wiz Khalifa — See You Again
    KnownVideo("papuvlVeZg8", 2016),     // Clean Bandit — Rockabye
    KnownVideo("kffacxfA7G4", 2010),     // Justin Bieber — Baby
    KnownVideo("k2qgadSvNyU", 2017),     // Dua Lipa — New Rules
    KnownVideo("Oextk-If8HQ", 2004),     // Keane — Somewhere Only We Know
    KnownVideo("UceaB4D0jpo", 2017),     // Post Malone — rockstar
    KnownVideo("v2AC41dglnM", 1990),     // AC/DC — Thunderstruck
    KnownVideo("hT_nvWreIhg", 2013),     // OneRepublic — Counting Stars
    KnownVideo("fKopy74weus", 2017),     // Imagine Dragons — Thunder
    KnownVideo("ZRtdQ81jPUQ", 2023),     // YOASOBI — Idol
    KnownVideo("T3E9Wjbq44E", 2011),     // Gym Class Heroes — Stereo Hearts
    KnownVideo("K0ibBPhiaG0", 2017),     // Ed Sheeran — Castle On The Hill
    KnownVideo("w2Ov5jzm3j8", 2019),     // Lil Nas X — Old Town Road
    KnownVideo("450p7goxZqg", 2013),     // John Legend — All of Me
    KnownVideo("ptSjNWnzpjg", 2008),     // Taylor Swift — Fearless
    KnownVideo("SMs0GnYze34", 2016),     // DJ Snake — Let Me Love You
    KnownVideo("NmCFY1oYDeM", 2016),     // John Legend — Love Me Now
    KnownVideo("bESGLojNYSo", 2008),     // Lady Gaga — Poker Face
    KnownVideo("Pkh8UtuejGw", 2019),     // Shawn Mendes & Camila Cabello — Señorita
    KnownVideo("Rt0spqQtMKg", 2006),     // SNL — D*** in a Box
    KnownVideo("YykjpeuMNEk", 2015),     // Coldplay — Hymn For The Weekend
)

// ═══════════════════════════════════════════════════════════════════════════
// FRAGMENT
// ═══════════════════════════════════════════════════════════════════════════

class VideoPlayerFragment : Fragment() {

    private var _binding: FragmentVideoPlayerBinding? = null
    private val binding get() = _binding!!

    // ── Video pool ─────────────────────────────────────────────────────────
    var currentVideoList: MutableList<ApiVideo> = mutableListOf()
    val playedVideoIds: MutableSet<String> = mutableSetOf()
    var duplicateSkipCount = 0
    private var currentVideo: ApiVideo? = null
    private var currentVideoMetadata: VideoMetadata? = null

    // ── Game state ─────────────────────────────────────────────────────────
    private var score = 0
    private var streak = 0
    private var hasGuessedThisRound = false
    private var currentDifficulty: Difficulty = Difficulty.MEDIUM

    // ── Timers (coroutine-based) ────────────────────────────────────────────
    private var countdownJob: Job? = null
    private var guessJob: Job? = null

    // ── Multiplayer ─────────────────────────────────────────────────────────
    private var isMultiplayer = false
    private var isNetworkClient = false
    private var pendingPlayerId: String? = null

    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVideoPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Load video pool (synchronous — pre-verified list)
        loadVideoPool()
        setupListeners()

        if (isMultiplayer) setupMultiplayerUI()

        if (!isMultiplayer) updateScoreDisplay()
        if (currentVideoList.isNotEmpty()) loadNextVideo()
    }

    override fun onResume() {
        super.onResume()
        // User may have returned from YouTube app — no special handling needed
    }

    override fun onDestroyView() {
        countdownJob?.cancel()
        guessJob?.cancel()
        _binding = null
        super.onDestroyView()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VIDEO POOL
    // ═══════════════════════════════════════════════════════════════════════════

    private fun loadVideoPool() {
        currentVideoList.clear()
        currentVideoList.addAll(fallbackVideoList.map {
            ApiVideo(it.id, it.year, 0L, "Music Video")
        })
        if (ENABLE_DEBUG_LOGS) Log.d(TAG, "Pool ready: ${currentVideoList.size} videos")
    }

    private fun pickCandidate(difficulty: Difficulty): ApiVideo? {
        // Filter videos that haven't been played yet this session
        val unscored = currentVideoList.filter { it.id !in playedVideoIds }
        if (unscored.isEmpty()) return null

        val now = System.currentTimeMillis()

        return when (difficulty) {
            Difficulty.EASY -> {
                // Pick oldest available video
                unscored.minByOrNull { it.year }
            }
            Difficulty.HARD -> {
                // Pick newest available video
                unscored.maxByOrNull { it.year }
            }
            Difficulty.MEDIUM -> {
                // Weight towards videos with years further from current year
                // to keep a balance
                unscored.maxByOrNull { kotlin.math.abs(it.year - 2000) }
            }
        } ?: unscored.firstOrNull()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UI LISTENERS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun setupListeners() {
        // "Watch on YouTube" button → opens YouTube app/browser
        binding.buttonWatchOnYouTube.setOnClickListener {
            currentVideo?.let { video -> openInYoutubeApp(video.id) }
        }

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

        // Toggle video overlay (audio-only mode)
        // Not needed with Intent-based approach — overlay is cosmetic only
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOAD & DISPLAY VIDEO
    // ═══════════════════════════════════════════════════════════════════════════

    private fun loadNextVideo() {
        if (currentVideoList.isEmpty()) {
            binding.progressBar.visibility = View.GONE
            Toast.makeText(requireContext(), R.string.error_no_videos_left, Toast.LENGTH_SHORT).show()
            return
        }

        countdownJob?.cancel()
        guessJob?.cancel()

        val candidate = pickCandidate(currentDifficulty) ?: run {
            // All played — reset
            playedVideoIds.clear()
            duplicateSkipCount = 0
            Toast.makeText(requireContext(), R.string.error_loading_video, Toast.LENGTH_SHORT).show()
            loadNextVideo()
            return
        }

        currentVideo = candidate
        playedVideoIds.add(candidate.id)
        hasGuessedThisRound = false

        // Reset UI
        binding.editTextGuess.text.clear()
        binding.editTextGuess.isEnabled = false
        binding.buttonGuess.isEnabled = false
        binding.textViewFeedback.visibility = View.GONE
        binding.textViewSongYear.visibility = View.GONE
        binding.buttonNextVideo.visibility = View.GONE
        binding.textViewHint.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        binding.buttonWatchOnYouTube.isEnabled = false

        if (isNetworkClient) {
            // Client mode: host sends the video info, we just display
            binding.textViewSongTitle.text = candidate.title
            binding.textViewArtist.text = getString(R.string.year_unknown)
            binding.progressBar.visibility = View.GONE
            beginGuessTimer()
        } else {
            // Fetch metadata from oEmbed (async)
            binding.textViewSongTitle.text = candidate.title
            lifecycleScope.launch {
                val metadata = fetchMetadata(candidate.id)
                currentVideoMetadata = metadata
                if (metadata != null) {
                    binding.textViewSongTitle.text = metadata.title
                    binding.textViewArtist.text = metadata.authorName
                    loadThumbnail(metadata.thumbnailUrl)
                }
                binding.progressBar.visibility = View.GONE
                beginCountdown(candidate.id)
            }
        }
    }

    // ── oEmbed Metadata Fetching ──────────────────────────────────────────

    /**
     * Fetch video metadata from YouTube's public oEmbed API.
     * No API key required — returns JSON with title, author_name, thumbnail_url.
     */
    private suspend fun fetchMetadata(videoId: String): VideoMetadata? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://www.youtube.com/oembed?url=" +
                    "https://www.youtube.com/watch?v=$videoId&format=json")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent",
                    "GuessTheSongYear/1.0 (Android)")
                val json = conn.inputStream.bufferedReader().use { it.readText() }

                // Simple JSON parsing — no external library needed
                val title = extractJsonString(json, "title") ?: "Music Video"
                val authorName = extractJsonString(json, "author_name") ?: "Unknown Artist"
                val thumbUrl = extractJsonString(json, "thumbnail_url")
                    ?: "https://img.youtube.com/vi/$videoId/mqdefault.jpg"

                VideoMetadata(title, authorName, thumbUrl)
            } catch (e: Exception) {
                if (ENABLE_DEBUG_LOGS) Log.e(TAG, "oEmbed failed for $videoId: ${e.message}")
                // Fallback to generic info
                null
            }
        }
    }

    /** Simple JSON string value extractor (no external library). */
    private fun extractJsonString(json: String, key: String): String? {
        val pattern = "\"${key}\"\\s*:\\s*\""
        val idx = json.indexOf(pattern)
        if (idx == -1) return null
        val start = idx + pattern.length
        val end = json.indexOf('"', start)
        if (end == -1) return null
        return json.substring(start, end)
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
    }

    // ── Thumbnail Loading ─────────────────────────────────────────────────

    private fun loadThumbnail(thumbnailUrl: String) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val url = URL(thumbnailUrl)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.doInput = true
                    conn.connect()
                    BitmapFactory.decodeStream(conn.inputStream)
                } catch (e: Exception) {
                    if (ENABLE_DEBUG_LOGS) Log.e(TAG, "Thumbnail load failed: ${e.message}")
                    null
                }
            }
            if (bitmap != null) {
                binding.imageViewThumbnail.setImageBitmap(bitmap)
            }
        }
    }

    // ── Open in YouTube App ───────────────────────────────────────────────

    private fun openInYoutubeApp(videoId: String) {
        try {
            // Try YouTube app first
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/watch?v=$videoId")).apply {
                setPackage("com.google.android.youtube")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
        } catch (e: Exception) {
            // YouTube app not installed — fall back to browser
            try {
                val intent = Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/watch?v=$videoId")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(requireContext(), R.string.error_no_youtube, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COUNTDOWN & GUESS TIMER
    // ═══════════════════════════════════════════════════════════════════════════

    private fun beginCountdown(videoId: String) {
        // 3-2-1 countdown, then enable guess
        val countdownSteps = listOf(3, 2, 1)
        countdownJob = lifecycleScope.launch {
            for (step in countdownSteps) {
                binding.textViewCountdown.text = step.toString()
                binding.textViewCountdown.visibility = View.VISIBLE
                delay(1.seconds)
            }
            binding.textViewCountdown.visibility = View.GONE

            // Countdown done — enable "Watch on YouTube" and start guess timer
            binding.buttonWatchOnYouTube.isEnabled = true
            beginGuessTimer()
        }
    }

    private fun beginGuessTimer() {
        // Guessing phase: user watches video on YouTube, then enters guess
        // Timer: 30 seconds for the whole phase
        binding.editTextGuess.isEnabled = true

        guessJob = lifecycleScope.launch {
            // Small delay for UI to settle
            delay(100)
            val totalMs = 30_000L
            val tickMs = 250L
            var elapsed = 0L

            while (elapsed < totalMs && !hasGuessedThisRound) {
                val remaining = totalMs - elapsed
                val seconds = (remaining / 1000).toInt() + 1
                binding.textViewCountdown.text = getString(R.string.video_countdown, seconds)
                binding.textViewCountdown.visibility = View.VISIBLE
                delay(tickMs)
                elapsed += tickMs
            }

            binding.textViewCountdown.visibility = View.GONE
            // Auto-submit if time runs out
            if (!hasGuessedThisRound) {
                submitGuess()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GUESS LOGIC
    // ═══════════════════════════════════════════════════════════════════════════

    private fun submitGuess() {
        if (hasGuessedThisRound) return
        val video = currentVideo ?: return

        guessJob?.cancel()
        binding.textViewCountdown.visibility = View.GONE

        val input = binding.editTextGuess.text.toString()
        if (input.isBlank()) {
            // Time's up or skipped — treat as wrong
            showAnswer(video.year, Int.MAX_VALUE)
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
        val diff = kotlin.math.abs(guessedYear - video.year)

        // Score calculation
        val points = when {
            diff == 0 -> 100
            diff <= 1 -> 75
            diff <= 2 -> 50
            diff <= 3 -> 30
            diff <= 5 -> 20
            diff <= 10 -> 10
            else -> 0
        }

        streak = if (points > 0) streak + 1 else 0
        val streakMultiplier = 1 + (streak / 3)
        val totalPoints = points * streakMultiplier

        score += totalPoints
        if (!isMultiplayer) updateScoreDisplay()

        // Feedback
        val feedback = when {
            diff == 0 -> getString(R.string.correct_exact)
            diff <= 2 -> getString(R.string.correct_very_close, diff)
            diff <= 5 -> getString(R.string.correct_close, diff)
            diff <= 10 -> getString(R.string.correct_ok, diff)
            else -> getString(R.string.wrong, video.year)
        }
        val pointsStr = if (totalPoints > 0) " (${getString(R.string.score_earned, totalPoints)})" else ""
        binding.textViewFeedback.text = "$feedback$pointsStr"
        binding.textViewFeedback.visibility = View.VISIBLE

        showAnswer(video.year, guessedYear)
    }

    private fun showAnswer(correctYear: Int, guessedYear: Int) {
        binding.textViewSongYear.text = getString(R.string.song_release_year, correctYear)
        binding.textViewSongYear.visibility = View.VISIBLE
        binding.textViewSongYear.setTextColor(
            if (guessedYear == correctYear) {
                ResourcesCompat.getColor(resources, R.color.amber_accent, null)
            } else {
                ResourcesCompat.getColor(resources, R.color.error_red, null)
            }
        )

        binding.editTextGuess.isEnabled = false
        binding.buttonGuess.isEnabled = false
        binding.buttonWatchOnYouTube.isEnabled = false
        binding.buttonNextVideo.visibility = View.VISIBLE
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SCORE DISPLAY
    // ═══════════════════════════════════════════════════════════════════════════

    private fun updateScoreDisplay() {
        binding.textViewScore.text = getString(R.string.score_label, score)
        if (streak > 0) {
            binding.textViewScore.append("  |  " + getString(R.string.streak_label, streak))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DIFFICULTY
    // ═══════════════════════════════════════════════════════════════════════════

    fun setDifficulty(difficulty: Difficulty) {
        currentDifficulty = difficulty
        if (ENABLE_DEBUG_LOGS) Log.d(TAG, "Difficulty set to $difficulty")
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

    fun resetGame() {
        playedVideoIds.clear()
        duplicateSkipCount = 0
        score = 0
        streak = 0
        if (!isMultiplayer) updateScoreDisplay()
        loadNextVideo()
    }

    fun getScore(): Int = score
    fun getStreak(): Int = streak
    fun getRemainingVideos(): Int = currentVideoList.size - playedVideoIds.size
    fun hasMoreVideos(): Boolean = playedVideoIds.size < currentVideoList.size

    // ═══════════════════════════════════════════════════════════════════════════
    // MULTIPLAYER (same-device hot-seat)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun setupMultiplayerUI() {
        isMultiplayer = true
        binding.textViewScore.visibility = View.GONE
        binding.textViewLeaderboard.visibility = View.VISIBLE
        binding.recyclerViewPlayers.visibility = View.VISIBLE
        binding.buttonRevealAnswers.visibility = View.VISIBLE
    }

    fun setMultiplayer(isMp: Boolean, players: List<String>? = null) {
        isMultiplayer = isMp
        if (!isAdded) return
        if (isMp) {
            setupMultiplayerUI()
        } else {
            isMultiplayer = false
            binding.textViewScore.visibility = View.VISIBLE
            binding.textViewLeaderboard.visibility = View.GONE
            binding.recyclerViewPlayers.visibility = View.GONE
            binding.buttonRevealAnswers.visibility = View.GONE
        }
    }

    private fun revealMultiplayerAnswers() {
        currentVideo?.let { video ->
            val answer = video.year.toString()
            Toast.makeText(requireContext(), "Riktig år: $answer", Toast.LENGTH_LONG).show()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NETWORK MULTIPLAYER (host/client via TCP)
    // ═══════════════════════════════════════════════════════════════════════════

    fun setNetworkClientMode() { isNetworkClient = true }

    /**
     * Called by host when a new video is selected.
     * Client receives: videoId|year|title|artist
     */
    fun loadVideoFromHost(videoId: String, year: Int, title: String, artist: String) {
        currentVideo = ApiVideo(videoId, year, 0L, title)
        currentVideoMetadata = VideoMetadata(title, artist,
            "https://img.youtube.com/vi/$videoId/mqdefault.jpg")
        playedVideoIds.add(videoId)
        hasGuessedThisRound = false

        binding.textViewSongTitle.text = title
        binding.textViewArtist.text = artist
        loadThumbnail("https://img.youtube.com/vi/$videoId/mqdefault.jpg")
        binding.progressBar.visibility = View.GONE
        beginGuessTimer()
    }

    fun setPendingPlayerId(playerId: String) {
        pendingPlayerId = playerId
    }

    fun submitGuessOnHost(guessedYear: Int) {
        if (isNetworkClient) return
        submitGuess()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COMPANION — factory + public API used by MainActivity
    // ═══════════════════════════════════════════════════════════════════════════

    companion object {
        private const val TAG = "VideoPlayerFragment"

        @JvmStatic
        fun newInstance(
            playerNames: List<String>? = null,
            showReveal: Boolean = false
        ): VideoPlayerFragment {
            val frag = VideoPlayerFragment()
            frag.arguments = Bundle().apply {
                if (playerNames != null) {
                    putStringArrayList("playerNames", ArrayList(playerNames))
                }
                putBoolean("showReveal", showReveal)
            }
            return frag
        }
    }

    // Public API called from MainActivity / other fragments
    fun getDuplicateCount(): Int = duplicateSkipCount

    fun resetDuplicateTracker() {
        playedVideoIds.clear()
        duplicateSkipCount = 0
    }

    fun getStats(): String = "$score|$streak|${playedVideoIds.size}"

    fun resetScore() {
        score = 0
        streak = 0
        if (!isMultiplayer) updateScoreDisplay()
    }

    // Simplified multiplayer — revealAnswers is handled via buttonRevealAnswers
}
