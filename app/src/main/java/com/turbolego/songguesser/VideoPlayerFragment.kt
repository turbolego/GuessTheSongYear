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
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.turbolego.songguesser.databinding.FragmentVideoPlayerBinding
import com.turbolego.songguesser.databinding.ItemPlayerGuessBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

private const val TAG = "VideoPlayerFragment"
private const val YEAR_MIN = 1960
private const val YEAR_MAX = 2025
private const val ENABLE_DEBUG_LOGS = false  // set to true for WebView debug
private const val PRELOAD_COUNT = 3           // buffer 3 videos ahead
private const val PRELOAD_INTERVAL_MS = 200L  // 200ms gap between cueVideoById calls
private val PERMANENT_EMBED_ERRORS = setOf(100, 101, 150, 152)   // Iframe error codes that mean "never try this video again"

data class KnownVideo(val id: String, val year: Int)
data class ApiVideo(val id: String, val year: Int, val views: Long, val title: String)

private val fallbackVideoList = listOf(
    // All verified embeddable via YouTube oEmbed API (HTTP 200).
    // Years sourced from YouTube metadata / Wikipedia.
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
    KnownVideo("ZRtdQ81jPUQ", 2023),     // YOASOBI — アイドル
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

private var currentVideoList: MutableList<ApiVideo> = mutableListOf()
val playedVideoIds: MutableSet<String> = mutableSetOf()
var duplicateSkipCount = 0

/** Videos validated as embeddable via oEmbed API. All videos in fallbackVideoList are pre-verified. */
private val embeddableVideoIds: MutableSet<String> = mutableSetOf()

/**
 * Load the video pool from the curated, oEmbed-verified fallback list.
 * All videos in this list have been pre-checked for embed permission
 * via YouTube's public oEmbed API (HTTP 200 = embeddable).
 *
 * Synchronous — no network calls needed at startup.
 */
private fun loadVideoPool() {
    Log.d(TAG, "Loading video pool (${fallbackVideoList.size} curated, oEmbed-verified videos)")

    embeddableVideoIds.clear()
    currentVideoList.clear()

    val candidates = fallbackVideoList.map {
        ApiVideo(it.id, it.year, 0L, "Music Video")
    }

    // All videos in fallbackVideoList are pre-verified embeddable.
    // Trust the list — no runtime oEmbed validation needed.
    embeddableVideoIds.addAll(candidates.map { it.id })
    currentVideoList.addAll(candidates)

    Log.d(TAG, "Pool ready: ${currentVideoList.size} videos")
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

    private var currentVideo: ApiVideo? = null
    private var isPlayerReady = false
    private var hasGuessedThisRound = false
    private var currentDifficulty: Difficulty = Difficulty.MEDIUM
    private var errorRetryCount = 0

    private var countdownJob: Job? = null
    private var loadVideoJob: Job? = null
    private var preloadJob: Job? = null

    /** Pre-loaded candidate video IDs for instant fallback on load error. */
    private val preloadedCandidates: MutableList<ApiVideo> = mutableListOf()

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

        // Load video pool first — synchronous, all pre-verified embeddable
        loadVideoPool()

        setupListeners()
        setupYouTubePlayer()

        if (isMultiplayer) setupMultiplayerUI()

        if (isNetworkClient) {
            // Client mode: register listener, no video pool (video from host)
            setupClientNetworkListener()
        } else {
            // Start preloading candidates immediately (pool already loaded)
            if (currentVideoList.isNotEmpty()) {
                lifecycleScope.launch {
                    preloadNextCandidates()
                }
            }
        }

        if (!isMultiplayer) updateScoreDisplay()
        binding.progressBar.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        countdownJob?.cancel()
        loadVideoJob?.cancel()
        preloadJob?.cancel()
        preloadedCandidates.clear()
        iframeServer?.shutdown()
        iframeServer = null
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

    /**
     * Bridge between WebView YouTube JS and Kotlin.
     * Methods called from YouTube Iframe API callbacks via JavaScript.
     *
     * All @JavascriptInterface methods run on a WebView internal thread —
     * must post to main looper for any UI touches.
     */
    inner class YouTubeBridge {
        @JavascriptInterface
        fun onReady() {
            requireActivity().runOnUiThread {
                if (ENABLE_DEBUG_LOGS) Log.d(TAG, "YouTube Iframe: onReady")
                isPlayerReady = true
                if (currentVideo == null) loadNextVideo()
                else beginCountdown(currentVideo!!.id)
            }
        }

        @JavascriptInterface
        fun onStateChange(state: Int) {
            requireActivity().runOnUiThread {
                // YouTube PlayerState:
                // -1 = unstarted, 0 = ended, 1 = playing, 2 = paused,
                //  3 = buffering, 5 = video cued
                if (state == 1) { // PLAYING
                    errorRetryCount = 0
                    if (ENABLE_DEBUG_LOGS) Log.d(TAG, "Playing: ${currentVideo?.id}")
                    enableGuessing()
                    if (currentDifficulty.hintEnabled && !isMultiplayer) showHint()
                    if (!isNetworkClient) preloadNextCandidates()
                }
            }
        }

        @JavascriptInterface
        fun onError(error: Int) {
            requireActivity().runOnUiThread {
                if (ENABLE_DEBUG_LOGS) Log.e(TAG, "Player error: $error (attempt ${errorRetryCount + 1})")

                // YouTube Iframe error code:
                //   2   = invalid parameter
                //   5   = HTML5 player error
                //   100 = video not found
                //   101 = embedding blocked by owner
                //   150 = embedding disabled by uploader
                //   152 = video unavailable

                // On permanent embed-block errors, remove the video from pool
                // so preloading never picks it again.
                if (error in PERMANENT_EMBED_ERRORS && currentVideo != null) {
                    val blockedId = currentVideo!!.id
                    currentVideoList.removeAll { it.id == blockedId }
                    embeddableVideoIds.remove(blockedId)
                    preloadedCandidates.removeAll { it.id == blockedId }
                    if (ENABLE_DEBUG_LOGS) Log.d(TAG, "Video $blockedId permanently removed (embed blocked)")
                }

                errorRetryCount++
                // Always try to switch — preloaded or fallback.
                autoSwitchVideoOnError()
            }
        }
    }

    // ── YouTube Iframe HTML ──────────────────────────────────────────────

    /**
     * Build the HTML page hosting the official YouTube Iframe Player API.
     * The 'Android' JS object is the bridge (added via addJavascriptInterface).
     */
    private fun buildIframeHtml(): String = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
            <style>
                * { margin: 0; padding: 0; }
                body { background: #000; overflow: hidden; }
                #player { width: 100vw; height: 100vh; }
            </style>
        </head>
        <body>
            <div id="player"></div>
            <script src="https://www.youtube.com/iframe_api"></script>
            <script>
                var player;
                function onYouTubeIframeAPIReady() {
                    player = new YT.Player('player', {
                        height: '100%',
                        width: '100%',
                        videoId: '',
                        host: 'https://www.youtube.com',
                        playerVars: {
                            'playsinline': 1,
                            'controls': 1,
                            'rel': 0,
                            'fs': 1,
                            'modestbranding': 1,
                            'iv_load_policy': 3,
                            'origin': window.location.origin
                        },
                        events: {
                            'onReady': onPlayerReady,
                            'onStateChange': onPlayerStateChange,
                            'onError': onPlayerError
                        }
                    });
                }

                function onPlayerReady(event) {
                    Android.onReady();
                }

                function onPlayerStateChange(event) {
                    Android.onStateChange(event.data);
                }

                function onPlayerError(event) {
                    Android.onError(event.data);
                }

                // ── Called from Kotlin via evaluateJavascript ──

                function loadVideo(videoId) {
                    player.loadVideoById(videoId);
                }

                function cueVideo(videoId) {
                    player.cueVideoById(videoId);
                }

                function playVideo() {
                    player.playVideo();
                }

                function pauseVideo() {
                    player.pauseVideo();
                }
            </script>
        </body>
        </html>
    """.trimIndent()

    // ── WebView helpers ──────────────────────────────────────────────────

    private fun loadVideoInWebView(videoId: String) {
        val webView = binding.youtubePlayerView as WebView
        webView.evaluateJavascript("javascript:loadVideo('$videoId')", null)
    }

    // ── Embedded HTTP server for serving iframe HTML ──────────────────────

    /** Local HTTP server that serves the iframe HTML page.
     * YouTube requires the embedding page to have a real HTTP origin
     * that matches the 'origin' playerVar. loadDataWithBaseURL creates a
     * data: or about:blank origin which YouTube blocks (error 150/152).
     *
     * This tiny server runs on 127.0.0.1:{iframeServerPort} and serves
     * exactly one page: the iframe HTML. The WebView loads it via
     * http://127.0.0.1:{port}/ — YouTube sees a real origin and allows
     * the embed.
     */
    private var iframeServer: FiwareHttpServer? = null
    private var iframeServerPort: Int = 0

    /** Minimal embedded HTTP server serving one HTML page on localhost. */
    private class FiwareHttpServer(private val html: String) : Thread() {
        @Volatile var running = true
        var port: Int = 0
        private var serverSocket: java.net.ServerSocket? = null

        override fun run() {
            try {
                serverSocket = java.net.ServerSocket(0)  // OS-assigned port
                port = serverSocket!!.localPort
                if (ENABLE_DEBUG_LOGS) Log.d(TAG, "Iframe server on http://127.0.0.1:$port")

                while (running) {
                    val client = try { serverSocket?.accept() } catch (_: Exception) { null } ?: continue
                    try {
                        val reader = client.getInputStream().bufferedReader()
                        // Read request line (ignore headers)
                        reader.readLine()
                        // Drain remaining headers
                        var line = reader.readLine()
                        while (!line.isNullOrEmpty() && line != "\r" && line != "") {
                            line = reader.readLine()
                        }

                        val response = buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: text/html; charset=UTF-8\r\n")
                            append("Content-Length: ${html.length}\r\n")
                            append("Connection: close\r\n")
                            append("Access-Control-Allow-Origin: *\r\n")
                            append("\r\n")
                            append(html)
                        }
                        client.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                        client.close()
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        fun shutdown() {
            running = false
            try { serverSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun cueVideoInWebView(videoId: String) {
        val webView = binding.youtubePlayerView as WebView
        webView.evaluateJavascript("javascript:cueVideo('$videoId')", null)
    }

    private fun setupYouTubePlayer() {
        val webView = binding.youtubePlayerView as WebView

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()

        // Bridge — exposed as 'Android' in JavaScript
        webView.addJavascriptInterface(YouTubeBridge(), "Android")

        // Start embedded HTTP server serving the iframe HTML.
        // YouTube requires a real origin (http://127.0.0.1) matching the
        // 'origin' playerVar. This avoids error 150/152.
        val html = buildIframeHtml()
        iframeServer = FiwareHttpServer(html)
        iframeServer!!.start()

        // Wait briefly for the server to bind
        var waited = 0
        while (iframeServer!!.port == 0 && waited < 500) {
            Thread.sleep(10)
            waited += 10
        }
        iframeServerPort = iframeServer!!.port

        // Load the iframe HTML from localhost — YouTube sees real origin
        webView.loadUrl("http://127.0.0.1:$iframeServerPort/")

        if (ENABLE_DEBUG_LOGS) {
            @Suppress("DEPRECATION")
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    // ── Video preloading (3 candidates buffered in WebView) ───────────────

    /**
     * Preload 3 next video candidates into the WebView via cueVideoById.
     * Cued videos are buffered but don't start playing. When the current
     * video fails, we immediately switch to the next cued candidate.
     *
     * Called at two points:
     * 1. After a video starts playing (onStateChange → PLAYING)
     * 2. Immediately when VideoPlayerFragment is created (so buffer 1 is
     *    ready before the first video even loads).
     */
    private fun preloadNextCandidates() {
        preloadJob?.cancel()
        preloadJob = lifecycleScope.launch {
            preloadedCandidates.clear()
            val seen = playedVideoIds.toMutableSet()
            seen.add(currentVideo?.id ?: "")

            for (i in 0 until PRELOAD_COUNT) {
                val candidate = pickCandidate(currentDifficulty, seen)
                if (candidate != null) {
                    cueVideoInWebView(candidate.id)
                    preloadedCandidates.add(candidate)
                    seen.add(candidate.id)
                    delay(PRELOAD_INTERVAL_MS)
                }
            }

            if (ENABLE_DEBUG_LOGS) {
                Log.d(TAG, "Preloaded ${preloadedCandidates.size} candidates: " +
                    preloadedCandidates.map { it.id }.joinToString())
            }
        }
    }

    /**
     * Like pickCandidate but excludes already-played + already-preloaded IDs.
     */
    private fun pickCandidate(difficulty: Difficulty, excludeIds: Set<String>): ApiVideo? {
        val pool = currentVideoList.filter { it.year in difficulty.yearRangeStart..difficulty.yearRangeEnd }
        if (pool.isEmpty()) return null
        val unplayed = pool.filter { it.id !in excludeIds }
        if (unplayed.isEmpty()) return null
        return unplayed.random()
    }

    /**
     * Consume the next preloaded candidate — use it as the video to play.
     * Called when current video fails to load (onError fallback).
     */
    private fun consumeNextPreloadedCandidate(): Boolean {
        if (preloadedCandidates.isEmpty()) return false
        val candidate = preloadedCandidates.removeAt(0)
        if (ENABLE_DEBUG_LOGS) Log.d(TAG, "Switching to preloaded: ${candidate.id}")
        currentVideo = candidate
        playedVideoIds.add(candidate.id)
        return true
    }

    private fun autoSwitchVideoOnError() {
        // Try preloaded candidate first — instant fallback (already buffered in WebView)
        if (preloadedCandidates.isNotEmpty()) {
            if (consumeNextPreloadedCandidate()) {
                // Call loadVideo (not cueVideo — preloaded was already cued, but
                // the previous video's error state may need a fresh load).
                // If THIS also fails, onError fires again with preloadedCandidates
                // still having remaining candidates.
                loadVideoInWebView(currentVideo!!.id)
                preloadNextCandidates()  // refill buffer
                return
            }
        }

        // Fallback: pick a new candidate the slow way
        lifecycleScope.launch {
            val candidate = pickCandidate(currentDifficulty)
            if (candidate != null) {
                Log.d(TAG, "Auto-switching to: ${candidate.id}")
                currentVideo = candidate
                playedVideoIds.add(candidate.id)
                try { cueVideoInWebView(candidate.id) } catch (_: Exception) {}
            } else {
                Toast.makeText(requireContext(), R.string.error_loading_video, Toast.LENGTH_LONG).show()
                binding.buttonNextVideo.visibility = View.VISIBLE
                binding.progressBar.visibility = View.GONE
            }
        }
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

            override fun onHostingStatus(status: String) {
                Log.d(TAG, "Client host status: $status")
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
                loadVideoInWebView(videoId)
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