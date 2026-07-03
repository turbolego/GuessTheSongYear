# GuessTheSongYear – Agent Instructions

## Project Overview
Android app (Kotlin, minSdk 24, targetSdk 35) where users guess the release year of a randomly selected music video. Single-activity, multi-fragment architecture using manual fragment transactions (not Jetpack Navigation, despite `nav_graph.xml` existing).

## Build & Run
```
./gradlew assembleDebug     # build debug APK
./gradlew build             # full build (debug + release)
./gradlew test              # unit tests
./gradlew connectedCheck    # instrumentation tests (requires device/emulator)
```
Release AAB is at `app/release/app-release.aab`.

## API Key Setup
The YouTube Data API v3 key is **not** in source control. Set it in `gradle.properties`:
```
youtube.api.key=YOUR_KEY_FROM_GOOGLE_CLOUD_CONSOLE
```
It is injected into code via `BuildConfig.YOUTUBE_API_KEY` (see `app/build.gradle.kts` `buildConfigField`).

## Architecture & Key Files
```
app/src/main/java/com/turbolego/songguesser/
  MainActivity.kt          – Single activity; hosts all fragments in R.id.fragment_container
  LoginFragment.kt         – Google Sign-In (legacy GoogleSignIn API); passes OAuth token to VideoPlayerFragment via Bundle
  VideoPlayerFragment.kt   – Core game screen: loads random video → 3-second countdown → plays → reveals year
  YouTubeFragment.kt       – Search UI; uses YouTubeViewModel + YouTubeAdapter (ListAdapter)
  YouTubeViewModel.kt      – AndroidViewModel; exposes StateFlow<YouTubeUiState>
  YouTubeModels.kt         – All shared data classes: SearchResponse, YouTubeVideo, YouTubeUiState, VideoInfo
  YouTubeApiService.kt     – Retrofit interface + Retrofit-backed YouTubeApiServiceImpl (used by YouTubeViewModel only)
  util/
    YouTubeApiService.kt   – OkHttp-backed YouTubeApiServiceImpl (used by VideoPlayerFragment directly)
    YouTubeAuthManager.kt  – Auth helper (partially used; LoginFragment duplicates some logic inline)
```

**Critical naming conflict:** There are **two** classes both named `YouTubeApiServiceImpl` in different packages:
- `com.turbolego.songguesser.YouTubeApiServiceImpl` – Retrofit-backed, used by `YouTubeViewModel`
- `com.turbolego.songguesser.util.YouTubeApiServiceImpl` – OkHttp-backed, used by `VideoPlayerFragment`

Always check the import when editing either.

## Authentication Dual-Mode Pattern
Both API key and OAuth paths exist throughout `util/YouTubeApiServiceImpl`. Methods come in pairs:
- `getRandomMusicVideo()` → uses stored API key
- `getRandomMusicVideoOAuth(accessToken)` → uses Bearer token

`VideoPlayerFragment` selects the path at runtime:
```kotlin
val videoInfo = if (accessToken != null) {
    youtubeApiService.getRandomMusicVideoOAuth(accessToken!!)
} else {
    youtubeApiService.getRandomMusicVideo()
}
```

## Fragment Navigation
Navigation is done via manual `supportFragmentManager.beginTransaction()` calls, **not** the nav graph. Back-stack is only added when navigating from `YouTubeFragment` to `VideoPlayerFragment`. Arguments are passed via `Bundle` (e.g., `"videoId"`, `"YOUTUBE_ACCESS_TOKEN"`).

## Release Year Extraction
Year is extracted from YouTube's `publishedAt` ISO-8601 string by taking the first 4 characters:
```kotlin
publishedAt.substring(0, 4).toInt()
```
This is the canonical pattern in both `YouTubeApiService` files. Do not use date parsing libraries for this field.

## Video Filtering
`util/YouTubeApiServiceImpl` filters videos to those with ≥1,000,000 views **and** `embeddable = true` before selecting. Fallback is hardcoded: `dQw4w9WgXcQ` (Rick Astley, 1987).

## ViewBinding vs findViewById
`LoginFragment` and `YouTubeFragment` use ViewBinding (`FragmentLoginBinding`, `FragmentYoutubeBinding`). `VideoPlayerFragment` uses `findViewById` — keep this inconsistency in mind when editing layouts used by that fragment.

## Google OAuth Client ID
Stored in `res/values/` as `@string/google_oauth_client_id_web`. Required for `GoogleSignInOptions.requestIdToken(...)` in both `LoginFragment` and `YouTubeAuthManager`.

## Dependency Versions (key entries in `gradle/libs.versions.toml`)
- AGP `9.2.1`, Kotlin `2.2.10`
- YouTube Player: `com.pierfrancescosoffritti.androidyoutubeplayer:core:12.1.0`
- YouTube Data API: `google-api-services-youtube:v3-rev20231011-2.0.0`
- OkHttp `4.12.0`, Retrofit `2.11.0`, Glide `4.16.0`

All library versions are managed via the version catalog; add new dependencies there rather than hardcoding in `app/build.gradle.kts`.

