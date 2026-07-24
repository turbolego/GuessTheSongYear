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
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.turbolego.songguesser.databinding.FragmentVideoPlayerBinding
import kotlinx.coroutines.*
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration.Companion.seconds

private const val TAG = "VideoPlayerFragment"
private const val YEAR_MIN = 1960
private const val YEAR_MAX = 2025
private const val ENABLE_DEBUG_LOGS = false

data class KnownVideo(val id: String, val year: Int)
data class ApiVideo(val id: String, val year: Int, val views: Long, val title: String)

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
    KnownVideo("YlUKcNNmywk", 1999),     // RHCP — Californication
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
    KnownVideo("Pkh8UtuejGw", 2019),     // Shawn Mendes — Señorita
    KnownVideo("Rt0spqQtMKg", 2006),     // SNL — D*** in a Box
    KnownVideo("YykjpeuMNEk", 2015),     // Coldplay — Hymn For The Weekend
)

// ═══════════════════════════════════════════════════════════════════════════
// SIMPLE HTTP DOWNLOADER FOR NEWPIPE EXTRACTOR
// ═══════════════════════════════════════════════════════════════════════════

class SimpleDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val url = URL(request.url())
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 30000
        conn.requestMethod = request.httpMethod()
        for ((key, value) in request.headers().entries) {
            conn.setRequestProperty(key, value.joinToString(", "))
        }
        conn.instanceFollowRedirects = false

        val responseCode = conn.responseCode
        val responseMessage = conn.responseMessage ?: ""
        val headers = mutableMapOf<String, MutableList<String>>()
        conn.headerFields?.forEach { (key, values) ->
            if (key != null) headers[key] = values.toMutableList()
        }

        val body = if (responseCode in 200..299) {
            try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } catch (_: Exception) { "" }
        } else {
            try {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) { "" }
        }

        val latestUrl = conn.url.toString()
        conn.disconnect()
        return Response(responseCode, responseMessage, headers, body, latestUrl)
    }
}

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

    // ── Game state ─────────────────────────────────────────────────────────
    private var score = 0
    private var streak = 0
    private var hasGuessedThisRound = false
    private var currentDifficulty: Difficulty = Difficulty.MEDIUM

    // ── Timers ─────────────────────────────────────────────────────────────
    private var countdownJob: Job? = null
    private var guessJob: Job? = null

    // ── ExoPlayer ──────────────────────────────────────────────────────────
    private var exoPlayer: ExoPlayer? = null
    private var playerStarted = false

    // ── NewPipe extractor state ────────────────────────────────────────────
    private var youtubeService: StreamingService? = null
    private var streamExtractJob: Job? = null

    // ── Multiplayer ────────────────────────────────────────────────────────
    private var isMultiplayer = false
    private var isNetworkClient = false
    private var pendingPlayerId: String? = null

    // ═══════════════════════════════════════════════════════════════════════════
    // INIT
    // ═══════════════════════════════════════════════════════════════════════════

    init {
        // Initialize NewPipe Extractor once
        try {
            NewPipe.init(SimpleDownloader())
            youtubeService = NewPipe.getService("YouTube")
            if (ENABLE_DEBUG_LOGS) Log.d(TAG, "NewPipe initialized ✓")
        } catch (e: Exception) {
            Log.e(TAG, "NewPipe init failed: ${e.message}")
        }
    }

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

        // Init ExoPlayer
        exoPlayer = ExoPlayer.Builder(requireContext()).build().also { player ->
            binding.playerView.player = player
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> {
                            if (ENABLE_DEBUG_LOGS) Log.d(TAG, "ExoPlayer READY")
                            binding.progressBar.visibility = View.GONE
                            if (!playerStarted) {
                                playerStarted = true
                                player.play()
                                // Start game countdown once video begins playing
                                beginCountdown()
                            }
                        }
                        Player.STATE_ENDED -> {
                            if (ENABLE_DEBUG_LOGS) Log.d(TAG, "ExoPlayer ENDED")
                        }
                        Player.STATE_BUFFERING -> {
                            binding.progressBar.visibility = View.VISIBLE
                        }
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying && ENABLE_DEBUG_LOGS) {
                        Log.d(TAG, "Video is now playing")
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.e(TAG, "ExoPlayer error: ${error.localizedMessage}")
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(),
                        R.string.error_loading_video, Toast.LENGTH_SHORT).show()
                }
            })
        }

        // Load video pool
        loadVideoPool()
        setupListeners()

        if (isMultiplayer) setupMultiplayerUI()
        if (!isMultiplayer) updateScoreDisplay()
        if (currentVideoList.isNotEmpty()) loadNextVideo()
    }

    override fun onResume() {
        super.onResume()
        exoPlayer?.play()
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }

    override fun onDestroyView() {
        countdownJob?.cancel()
        guessJob?.cancel()
        streamExtractJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
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
        val unscored = currentVideoList.filter { it.id !in playedVideoIds }
        if (unscored.isEmpty()) return null

        return when (difficulty) {
            Difficulty.EASY -> unscored.minByOrNull { it.year }
            Difficulty.HARD -> unscored.maxByOrNull { it.year }
            Difficulty.MEDIUM -> {
                unscored.maxByOrNull { kotlin.math.abs(it.year - 2000) }
            }
        } ?: unscored.firstOrNull()
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

        // Toggle overlay
        binding.buttonToggleVideo.setOnClickListener { toggleOverlay() }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOAD & PLAY VIDEO VIA NEWPIPE + EXOPLAYER
    // ═══════════════════════════════════════════════════════════════════════════

    private fun loadNextVideo() {
        if (currentVideoList.isEmpty()) {
            binding.progressBar.visibility = View.GONE
            Toast.makeText(requireContext(), R.string.error_no_videos_left, Toast.LENGTH_SHORT).show()
            return
        }

        countdownJob?.cancel()
        guessJob?.cancel()
        streamExtractJob?.cancel()

        val candidate = pickCandidate(currentDifficulty) ?: run {
            playedVideoIds.clear()
            duplicateSkipCount = 0
            Toast.makeText(requireContext(), R.string.error_loading_video, Toast.LENGTH_SHORT).show()
            loadNextVideo()
            return
        }

        currentVideo = candidate
        playedVideoIds.add(candidate.id)
        hasGuessedThisRound = false
        playerStarted = false

        // Reset UI
        binding.editTextGuess.text.clear()
        binding.editTextGuess.isEnabled = false
        binding.buttonGuess.isEnabled = false
        binding.textViewFeedback.visibility = View.GONE
        binding.textViewSongYear.visibility = View.GONE
        binding.textViewSongYear.text = ""
        binding.buttonNextVideo.visibility = View.GONE
        binding.textViewHint.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        binding.textViewSongTitle.text = getString(R.string.loading_video)
        binding.textViewArtist.text = ""
        binding.textViewCountdown.visibility = View.GONE

        // Stop current playback
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()

        if (isNetworkClient) {
            // Client mode: host sends the info
            binding.textViewSongTitle.text = candidate.title
            binding.textViewArtist.text = getString(R.string.year_unknown)
            binding.progressBar.visibility = View.GONE
            beginGuessTimer()
        } else {
            // Extract stream URL and play it
            streamExtractJob = lifecycleScope.launch {
                extractAndPlay(candidate.id)
            }
        }
    }

    /**
     * Extract YouTube stream URL via NewPipe Extractor, then play in ExoPlayer.
     */
    private suspend fun extractAndPlay(videoId: String) {
        withContext(Dispatchers.IO) {
            try {
                val service = youtubeService
                    ?: throw IllegalStateException("NewPipe not initialized")

                val url = "https://www.youtube.com/watch?v=$videoId"
                val streamInfo = StreamInfo.getInfo(service, url)

                // Extract video + audio streams
                val videoStreams = streamInfo.videoStreams
                val audioStreams = streamInfo.audioStreams
                val videoOnlyStreams = streamInfo.videoOnlyStreams

                if (ENABLE_DEBUG_LOGS) {
                    Log.d(TAG, "Streams for $videoId:" +
                            " video=${videoStreams.size}, audio=${audioStreams.size}," +
                            " videoOnly=${videoOnlyStreams.size}")
                }

                // Find the best combined video+audio stream (progressive)
                var streamUrl: String? = null

                // Try progressive streams (combined audio+video) first
                if (videoStreams.isNotEmpty()) {
                    // Pick the highest quality progressive stream by resolution string (e.g. "720p")
                    val best = videoStreams
                        .filter { it.isVideoOnly == false }
                        .maxByOrNull { parseResolution(it.resolution) }
                    streamUrl = best?.url
                    if (ENABLE_DEBUG_LOGS) {
                        Log.d(TAG, "Progressive stream: ${best?.resolution}")
                    }
                }

                // Fall back to video-only stream
                if (streamUrl == null && videoOnlyStreams.isNotEmpty()) {
                    val bestVideo = videoOnlyStreams
                        .maxByOrNull { parseResolution(it.resolution) }
                    streamUrl = bestVideo?.url

                    if (ENABLE_DEBUG_LOGS) {
                        Log.d(TAG, "Video-only: ${bestVideo?.resolution}")
                    }
                }

                // Last resort: any video or audio URL
                if (streamUrl == null && audioStreams.isNotEmpty()) {
                    streamUrl = audioStreams.first().url
                    if (ENABLE_DEBUG_LOGS) Log.d(TAG, "Falling back to audio-only stream")
                }

                if (streamUrl == null) {
                    throw IOException("No playable streams found for video $videoId")
                }

                if (ENABLE_DEBUG_LOGS) Log.d(TAG, "Playing URL: ${streamUrl.take(80)}...")

                // Update UI on main thread
                withContext(Dispatchers.Main) {
                    val metadata = streamInfo.name?.let { name ->
                        VideoMetadata(
                            name,
                            streamInfo.uploaderName ?: "Unknown",
                            streamInfo.thumbnails?.firstOrNull()?.url
                                ?: "https://img.youtube.com/vi/$videoId/mqdefault.jpg"
                        )
                    }
                    currentVideoMetadata = metadata
                    binding.textViewSongTitle.text = metadata?.title ?: "Music Video"
                    binding.textViewArtist.text = metadata?.authorName ?: ""

                    // Prepare ExoPlayer with the stream URL
                    val mediaItem = MediaItem.fromUri(streamUrl)
                    exoPlayer?.setMediaItem(mediaItem)
                    exoPlayer?.prepare()

                    binding.progressBar.visibility = View.GONE
                }

            } catch (e: Exception) {
                Log.e(TAG, "Extraction failed for $videoId: ${e.message}")
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(),
                        getString(R.string.error_loading_video), Toast.LENGTH_SHORT).show()
                    // Try next video
                    loadNextVideo()
                }
            }
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
                delay(1.seconds)
            }
            binding.textViewCountdown.visibility = View.GONE
            beginGuessTimer()
        }
    }

    private fun beginGuessTimer() {
        binding.editTextGuess.isEnabled = true

        guessJob = lifecycleScope.launch {
            delay(100)
            val totalMs = 30_000L
            val tickMs = 250L
            var elapsed = 0L

            while (elapsed < totalMs && !hasGuessedThisRound) {
                val remaining = totalMs - elapsed
                val seconds = (remaining / 1000).toInt() + 1
                binding.textViewCountdown.text = "$seconds"
                binding.textViewCountdown.visibility = View.VISIBLE
                delay(tickMs)
                elapsed += tickMs
            }

            binding.textViewCountdown.visibility = View.GONE
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
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // OVERLAY TOGGLE
    // ═══════════════════════════════════════════════════════════════════════════

    private fun toggleOverlay() {
        val isHidden = binding.videoOverlay.visibility == View.VISIBLE
        binding.videoOverlay.visibility = if (isHidden) View.GONE else View.VISIBLE
        binding.textViewOverlayLabel.visibility = if (isHidden) View.GONE else View.VISIBLE
        binding.buttonToggleVideo.text = getString(
            if (isHidden) R.string.overlay_audio_only else R.string.btn_show_video
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HINTS
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
    // MULTIPLAYER
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
    // NETWORK CLIENT
    // ═══════════════════════════════════════════════════════════════════════════

    fun setNetworkClientMode() { isNetworkClient = true }

    fun loadVideoFromHost(videoId: String, year: Int, title: String, artist: String) {
        currentVideo = ApiVideo(videoId, year, 0L, title)
        playedVideoIds.add(videoId)
        hasGuessedThisRound = false
        playerStarted = false

        binding.textViewSongTitle.text = title
        binding.textViewArtist.text = artist
        binding.progressBar.visibility = View.GONE

        // Play via NewPipe too (host sends the ID but we extract ourselves)
        streamExtractJob = lifecycleScope.launch {
            extractAndPlay(videoId)
        }
    }

    fun setPendingPlayerId(playerId: String) {
        pendingPlayerId = playerId
    }

    fun submitGuessOnHost(guessedYear: Int) {
        if (isNetworkClient) return
        submitGuess()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COMPANION + PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    companion object {
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

    data class VideoMetadata(
        val title: String,
        val authorName: String,
        val thumbnailUrl: String
    )

    private var currentVideoMetadata: VideoMetadata? = null

    /**
     * Parse resolution string like "720p", "1080p", "360p" to numeric height.
     */
    private fun parseResolution(resolution: String?): Int {
        if (resolution == null) return 0
        val digits = resolution.filter { it.isDigit() }
        return digits.toIntOrNull() ?: 0
    }
}
