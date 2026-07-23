# Replace InnerTube with WebView + Iframe Player — Implementation Plan

> **For Hermes:** Use plan mode — this document is the specification. Execute task-by-task.

**Goal:** Remove the reverse-engineered InnerTube YouTube API and
`com.pierfrancescosoffritti.androidyoutubeplayer` library. Replace with a
WebView-based YouTube Iframe Player that requires no API keys, no user login,
and fully complies with YouTube ToS. Keep the hardcoded fallback video list
as the **only** video source (InnerTube was unreliable and ToS-violating anyway).

**Architecture:** The YouTube Player library dependency is removed. A standard
Android `WebView` loads a minimal HTML page containing the official YouTube
Iframe Player API. The WebView replaces `YouTubePlayerView` in the layout.
Video source becomes the hardcoded fallback list directly — no API calls at all.

**Tech Stack:** Android WebView, YouTube Iframe Player API (JS), Kotlin, Coroutines

**Working repo:** `/tmp/GuessTheSongYear` (branch: `master`)

⚠️ **CRITICAL:** The existing `com.pierfrancescosoffritti.androidyoutubeplayer`
library retrieves YouTube stream URLs through InnerTube internally. The entire
package must be removed, not just usage — its mere presence as a dependency is
the ToS violation.

---

## Tasks

### Task 1: Remove YouTube Player dependency

**Objective:** Strip the InnerTube-based player dependency and all its usages from the build configuration.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/proguard-rules.pro`

**Step 1: Remove from version catalog**

In `gradle/libs.versions.toml`, remove the `youtubePlayer` version declaration:

```diff
- youtubePlayer = "13.0.0"
```

And remove the library entry:

```diff
- youtube-android-player = { group = "com.pierfrancescosoffritti.androidyoutubeplayer",
-    name = "core", version.ref = "youtubePlayer" }
```

**Step 2: Remove from build.gradle.kts**

In `app/build.gradle.kts`, remove the implementation line:

```diff
-    // YouTube iframe player (InnerTube-based — ⚠️ ToS violation)
-    implementation(libs.youtube.android.player)
```

**Step 3: Remove ProGuard rule**

In `app/proguard-rules.pro`, remove:

```diff
- -keep class com.pierfrancescosoffritti.androidyoutubeplayer.** { *; }
```

**Verification:** `grep -r "pierfrancescosoffritti\|youtube-android-player\|androidyoutubeplayer" gradle/ app/build.gradle.kts app/proguard-rules.pro` should return no results.

**Commit:**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/proguard-rules.pro
git commit -m "chore: remove InnerTube-based YouTube Player dependency (ToS compliance)"
```

---

### Task 2: Remove YouTubeSearchService entirely

**Objective:** Delete the reverse-engineered InnerTube API service. The hardcoded fallback list becomes the **sole** video source.

**Files:**
- Delete: `app/src/main/java/com/turbolego/songguesser/YouTubeSearchService.kt`
- Delete: `app/src/main/java/com/turbolego/songguesser/YouTubeModels.kt`
- Modify: `app/src/main/java/com/turbolego/songguesser/VideoPlayerFragment.kt`

**Step 1: Delete InnerTube service**

```bash
rm app/src/main/java/com/turbolego/songguesser/YouTubeSearchService.kt
rm app/src/main/java/com/turbolego/songguesser/YouTubeModels.kt
```

**Step 2: Remove imports and references from VideoPlayerFragment.kt**

Remove `import` statement for YouTubeSearchService (if any — check current code).

Replace `fetchVideosFromApi()` with a direct fallback usage:

```kotlin
// BEFORE (current):
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

// AFTER (TOS-compliant):
private fun loadVideoPool() {
    Log.d(TAG, "Loading video pool (${fallbackVideoList.size} curated videos)")
    currentVideoList.clear()
    currentVideoList.addAll(fallbackVideoList.map {
        ApiVideo(it.id, it.year, it.views, "Music Video") // title placeholder — WebView doesn't need it
    })
}
```

Also update the call site: change the launch block that calls `fetchVideosFromApi()` to call `loadVideoPool()` instead.

**Verification:** `grep -r "YouTubeSearchService\|InnerTube\|searchMusicVideos" app/src/main/java/` should return no results (except possibly in comments/docs).

**Commit:**

```bash
git add app/src/main/java/com/turbolego/songguesser/
git commit -m "refactor: remove InnerTube API service — use curated fallback only"
```

---

### Task 3: Remove OkHttp dependency (if no other usage)

**Objective:** OkHttp was used exclusively for InnerTube API calls. Verify no other usage and remove.

**Files:**
- Inspect: all `.kt` files under `app/src/main/java/`
- Potentially modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`

**Step 1: Verify OkHttp usage**

```bash
grep -r "okhttp\|OkHttp" app/src/main/java/ --include='*.kt'
```

If OkHttp is used elsewhere (unlikely — the `HostGameService`/`JoinGameService` use raw `java.net.Socket`, not OkHttp), skip this task.

**Step 2: If unused, remove from build**

In `gradle/libs.versions.toml`, remove:

```diff
- okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version = "4.12.0" }
```

In `app/build.gradle.kts`, remove:

```diff
-    implementation(libs.okhttp)
```

In `app/proguard-rules.pro`, remove:

```diff
- -dontwarn okhttp3.**
- -dontwarn okio.**
```

**Verification:** `grep -r "okhttp\|okio" gradle/ app/build.gradle.kts` returns 0 results.

**Commit:**

```bash
git commit -m "chore: remove OkHttp dependency (only used by InnerTube API)"
```

---

### Task 4: Replace YouTubePlayerView with WebView in layout

**Objective:** Swap the native `YouTubePlayerView` for a standard `WebView` in the video player layout. Remove custom attributes.

**Files:**
- Modify: `app/src/main/res/layout/fragment_video_player.xml`
- Modify: `app/src/main/res/values/attrs.xml` (remove `declare-styleable` for YouTubePlayerView)

**Step 1: Replace in fragment_video_player.xml**

Current (lines 9-18):

```xml
    <!-- YouTube Player -->
    <com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
        android:id="@+id/youtube_player_view"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:layout_constraintDimensionRatio="16:9"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent"
        app:showFullScreenButton="true"
        app:enableAutomaticInitialization="false" />
```

Replace with:

```xml
    <!-- YouTube Player (WebView + Iframe API — ToS-compliant) -->
    <WebView
        android:id="@+id/youtube_player_view"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:layout_constraintDimensionRatio="16:9"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent" />
```

**Step 2: Remove YouTubePlayerView attrs.xml entry**

In `app/src/main/res/values/attrs.xml`, remove the `declare-styleable` block (currently lines 3-6):

```diff
-    <!-- Custom attributes for YouTubePlayerView from com.pierfrancescosoffritti.androidyoutubeplayer -->
-    <declare-styleable name="YouTubePlayerView">
-        <attr name="showFullScreenButton" format="boolean" />
-        <attr name="enableAutomaticInitialization" format="boolean" />
-    </declare-styleable>
```

**Verification:** The layout XML should have no references to `com.pierfrancescosoffritti`. `showFullScreenButton` and `enableAutomaticInitialization` app namespace attributes gone.

**Commit:**

```bash
git add app/src/main/res/layout/fragment_video_player.xml app/src/main/res/values/attrs.xml
git commit -m "refactor: replace YouTubePlayerView with WebView for Iframe API"
```

---

### Task 5: Implement WebView-based YouTube player in VideoPlayerFragment

**Objective:** Rewrite the player initialization to use WebView loading the official YouTube Iframe Player API. Maintain all existing behavior: onReady callback, player state tracking, error handling, loading/cuing videos.

**Files:**
- Modify: `app/src/main/java/com/turbolego/songguesser/VideoPlayerFragment.kt`

**Step 1: Add WebView import and remove YouTube player imports**

```diff
- import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
- import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
- import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
- import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
+ import android.webkit.JavascriptInterface
+ import android.webkit.WebView
+ import android.webkit.WebViewClient
+ import android.webkit.WebChromeClient
```

**Step 2: Add JavaScript interface for Kotlin↔JS bridge**

Add an inner class inside `VideoPlayerFragment`:

```kotlin
/**
 * Bridge between WebView YouTube JS and Kotlin.
 * Methods called from YouTube Iframe API callbacks via JavaScript.
 */
inner class YouTubeBridge {
    @JavascriptInterface
    fun onReady() {
        requireActivity().runOnUiThread {
            if (BuildConfig.DEBUG) Log.d(TAG, "YouTube Iframe: onReady")
            isPlayerReady = true
            if (currentVideo == null) loadNextVideo()
            else beginCountdown(currentVideo!!.id)
        }
    }

    @JavascriptInterface
    fun onStateChange(state: Int) {
        requireActivity().runOnUiThread {
            // YouTube PlayerState: -1=unstarted, 0=ended, 1=playing, 2=paused, 3=buffering, 5=video cued
            if (state == 1) { // PLAYING
                errorRetryCount = 0
                if (BuildConfig.DEBUG) Log.d(TAG, "Playing: ${currentVideo?.id}")
                enableGuessing()
                if (currentDifficulty.hintEnabled && !isMultiplayer) showHint()
            }
        }
    }

    @JavascriptInterface
    fun onError(error: Int) {
        requireActivity().runOnUiThread {
            if (BuildConfig.DEBUG) Log.e(TAG, "Player error: $error (attempt ${errorRetryCount + 1})")
            errorRetryCount++
            if (errorRetryCount >= MAX_RETRY_ATTEMPTS) {
                Toast.makeText(requireContext(), R.string.error_loading_video, Toast.LENGTH_LONG).show()
                binding.buttonNextVideo.visibility = View.VISIBLE
                binding.progressBar.visibility = View.GONE
            } else {
                // Retry with next candidate
                autoSwitchVideoOnError()
            }
        }
    }
}
```

**Step 3: Rewrite `setupYouTubePlayer()`**

The current method (lines 272-318) uses `binding.youtubePlayerView.initialize(...)` with the library's callback style. Rewrite entirely:

```kotlin
private fun setupYouTubePlayer() {
    val webView = binding.youtubePlayerView as WebView

    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false  // allow autoplay
    }
    webView.webChromeClient = WebChromeClient() // enables fullscreen JS, media controls
    webView.webViewClient = WebViewClient()

    webView.addJavascriptInterface(YouTubeBridge(), "Android") // bridge exposed as 'Android' in JS

    // Load the iframe HTML
    val html = buildIframeHtml()
    webView.loadDataWithBaseURL(
        "https://www.youtube.com",
        html,
        "text/html",
        "UTF-8",
        null
    )

    if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true) // Chrome DevTools for WebView
}
```

**Step 4: Add `buildIframeHtml()` method**

Returns the HTML string containing the YouTube Iframe Player API:

```kotlin
/**
 * Build the HTML page hosting YouTube Iframe Player API.
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
                    playerVars: {
                        'playsinline': 1,
                        'controls': 1,
                        'rel': 0,            // no related videos at end
                        'fs': 1,             // fullscreen button enabled
                        'modestbranding': 1,
                        'iv_load_policy': 3  // hide annotations
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

            // Called from Kotlin to load/cue a video
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
```

**Step 5: Replace `loadVideo()` and `cueVideo()` calls**

Current calls to `youTubePlayer?.loadVideo(...)` / `youTubePlayer?.cueVideo(...)` become `evaluateJavascript(...)` calls on the WebView. Add helper methods:

```kotlin
private fun loadVideoInWebView(videoId: String) {
    val webView = binding.youtubePlayerView as WebView
    webView.evaluateJavascript("javascript:loadVideo('$videoId')", null)
}

private fun cueVideoInWebView(videoId: String) {
    val webView = binding.youtubePlayerView as WebView
    webView.evaluateJavascript("javascript:cueVideo('$videoId')", null)
}
```

Find all `youTubePlayer?.loadVideo(` calls and replace with `loadVideoInWebView(videoId)`.
Find all `youTubePlayer?.cueVideo(` calls and replace with `cueVideoInWebView(videoId)`.
Find `youTubePlayer?.play()` and `youTubePlayer?.pause()` → `webView.evaluateJavascript("javascript:playVideo()", null)` and `webView.evaluateJavascript("javascript:pauseVideo()", null)`.

**Step 6: Replace `youTubePlayer` field**

```diff
- private var youTubePlayer: YouTubePlayer? = null
```

(No replacement needed — the field is not used after Step 5.)

Remove references to:
- `IFramePlayerOptions` (the builder in old code)
- `AbstractYouTubePlayerListener` (replaced by WebView JS bridge)
- All imports from `com.pierfrancescosoffritti`

**Step 7: Update error retry logic**

In the old code `autoSwitchVideoOnError()` calls `youTubePlayer?.cueVideo(...)`. Replace with `cueVideoInWebView(...)`.

**Verification:** 
- `grep -r "pierfrancescosoffritti\|AbstractYouTubePlayer\|YouTubePlayer\b\|IFramePlayerOptions\|youTubePlayer\b" app/src/main/java/VideoPlayerFragment.kt` returns 0 results.
- Build succeeds: `./gradlew assembleDebug`

**Commit:**

```bash
git add app/src/main/java/com/turbolego/songguesser/VideoPlayerFragment.kt
git commit -m "refactor: WebView-based YouTube Iframe Player (ToS-compliant, no keys)"
```

---

### Task 6: Handle multi-client video sync (multiplayer)

**Objective:** In multiplayer mode, the host sends video IDs to clients. Clients must load the same video in their WebView. Ensure `loadVideoInWebView` works correctly when called from `onVideoReceived` callback.

**Files:**
- Modify: `app/src/main/java/com/turbolego/songguesser/VideoPlayerFragment.kt`

**Step 1: Verify the `onVideoReceived` callback**

Find the `onVideoReceived` override in VideoPlayerFragment. It currently creates an `ApiVideo` and sets `currentVideo`, then calls `beginCountdown()` which calls `loadVideoInWebView(videoId)`.

No code change needed if `beginCountdown()` → `loadVideo()` → now calls `loadVideoInWebView(videoId)`.

**Step 2: Test**

This is inherently tested by the multiplayer flow. Host picks video → sends VIDEO message → client receives, loads same video via WebView.

**Verification:** Manual multiplayer test (2 devices on same network). But code review: trace the `onVideoReceived(videoId)` → `beginCountdown(videoId)` → `loadVideo(videoId)` call chain. All should now route through `loadVideoInWebView()`.

**Commit:**

```bash
git commit -m "test: verify multiplayer video sync works with WebView player"
```

(Only commit if changes were needed — otherwise skip.)

---

### Task 7: Full build verification

**Objective:** Verify the entire app builds and has no dependency on the removed library or InnerTube code.

**Files:**
- All (build only)

**Step 1: Clean build**

```bash
cd /tmp/GuessTheSongYear
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew clean assembleDebug --no-daemon
```

**Expected:** BUILD SUCCESSFUL. Zero `e:` errors.

**Step 2: Verify dependency tree**

```bash
./gradlew app:dependencies --configuration debugRuntimeClasspath | grep -iE "pierfrancesco|androidyoutubeplayer|okhttp"
```

**Expected:** No matches (or only OkHttp if it was kept for other uses).

**Step 3: Verify no InnerTube code references**

```bash
grep -r "InnerTube\|YouTubeSearchService\|searchMusicVideos\|innerTube\|YouTubeModels\|androidyoutubeplayer\|pierfrancesco" app/src/main/java/ --include='*.kt' | grep -v '//' | grep -v 'AGENTS\|APP_STRUCTURE\|SECURITY\|PROTOCOL'
```

**Expected:** No results in Kotlin source files. Comments in documentation files (AGENTS.md, SECURITY.md, etc.) are OK.

**Commit:**

```bash
git commit -m "build: clean build verification — InnerTube completely removed"
```

---

### Task 8: Update documentation

**Objective:** Reflect the ToS-compliant video source in project docs.

**Files:**
- Modify: `AGENTS.md`
- Modify: `SECURITY.md`
- Modify: `APP_STRUCTURE.md`
- Modify: `PROTOCOL.md` (if it mentions InnerTube)

**AGENTS.md changes:**
- Remove "YouTubeSearchService.kt InnerTube API" from key files table
- Update stack description: replace "InnerTube API" with "Curated video list"
- Remove InnerTube/ApiVideo from features list
- Add "WebView + YouTube Iframe Player API (ToS-compliant, no keys)"

**SECURITY.md changes:**
- Mark CRITICAL finding "InnerTube Reverse-Engineered API" as **RESOLVED** ✅
- Add note: "Replaced by WebView + official YouTube Iframe Player API (no API key, no ToS violation)"

**APP_STRUCTURE.md changes:**
- Remove YouTubeSearchService.kt and YouTubeModels.kt from architecture tree
- Update dependencies table: remove YouTube Player 13.0.0 row
- Update known issues: remove InnerTube API issue

**Verification:** Read updated files — InnerTube should be marked as removed/resolved, not as an active component.

**Commit:**

```bash
git add AGENTS.md SECURITY.md APP_STRUCTURE.md PROTOCOL.md
git commit -m "docs: mark InnerTube removal complete — ToS compliance achieved"
```

---

## Summary

| File | Action |
|------|--------|
| `gradle/libs.versions.toml` | Remove youtubePlayer + okhttp versions |
| `app/build.gradle.kts` | Remove youtube-android-player + okhttp impl |
| `app/proguard-rules.pro` | Remove player/okhttp keep rules |
| `YouTubeSearchService.kt` | **Delete** |
| `YouTubeModels.kt` | **Delete** |
| `fragment_video_player.xml` | Replace YouTubePlayerView → WebView |
| `attrs.xml` | Remove YouTubePlayerView declare-styleable |
| `VideoPlayerFragment.kt` | Rewrite player init, add JS bridge, remove library imports |
| `AGENTS.md` | Update stack, key files |
| `SECURITY.md` | Mark InnerTube CRITICAL as resolved |
| `APP_STRUCTURE.md` | Update architecture, dependencies |

**Final state:** App plays YouTube videos through official Iframe Player API via WebView.
Zero API keys. Zero ToS violations. Zero developer tokens. Single video source:
curated hardcoded list (~40 known music videos). InnerTube and
`com.pierfrancescosoffritti.androidyoutubeplayer` library are **completely
removed** — not just unused, not just stub-deprecated, but deleted from build
and disk.

---

## Risks

1. **Iframe Player ads:** Standard YouTube ads may play. The `playerVars` don't disable ads (YouTube controls that). This is expected — the Iframe API shows the same experience as youtube.com.
2. **WebView performance on older devices:** WebView + JS bridge is slightly slower than native player. Acceptable for a casual game.
3. **JS bridge threading:** `@JavascriptInterface` methods run on a WebView internal thread, not the main thread. Must use `runOnUiThread` for all UI operations (done in the implementation).
4. **No dynamic video pool:** Without an API, the video list is static (~40 songs). Games may repeat videos after 40 rounds. Acceptable — the fallback was already the primary experience.

---

## After Implementation — Next Steps

- [ ] Consider expanding the curated video list (add more KnownVideo entries)
- [ ] Consider a community-curated video list fetched from a JSON file on GitHub (no API key needed, compliant)
- [ ] Test on actual devices — verify WebView + Iframe works on Android 7–14