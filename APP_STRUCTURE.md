# GuessTheSongYear - App Structure Overview

## Project Overview
Android app (Kotlin, minSdk 24, targetSdk 37) where users guess the release year of a randomly selected music video. Single-activity, multi-fragment architecture using manual fragment transactions.

## Architecture

```
app/src/main/java/com/turbolego/songguesser/
├── MainActivity.kt                    # Single activity; hosts all fragments
├── VideoPlayerFragment.kt            # Core game screen
├── YouTubeSearchService.kt           # InnerTube API service
└── util/                              # (Empty - was mentioned in AGENTS.md)
```

## Layouts

```
app/src/main/res/layout/
├── activity_main.xml                 # Main activity layout (Toolbar + FragmentContainer)
└── fragment_video_player.xml        # Video player fragment layout
```

## Resources

```
app/src/main/res/values/
├── strings.xml                       # All string resources
└── auth_config.xml                   # OAuth client ID string reference
```

## Build Configuration

### Key Files
- `build.gradle.kts` - App-level build configuration
- `gradle/libs.versions.toml` - Version catalog
- `gradle.properties` - Project-wide Gradle settings

### Dependencies
| Library | Version | Purpose |
|---------|---------|---------|
| AndroidX Core KTX | 1.19.0 | Kotlin extensions |
| AppCompat | 1.7.1 | Compatibility library |
| Material | 1.14.0 | Material Design components |
| ConstraintLayout | 2.2.1 | Layout library |
| YouTube Player | 13.0.0 | YouTube player integration |
| Kotlinx Coroutines | 1.11.0 | Async operations |
| OkHttp | 4.12.0 | HTTP client for InnerTube API |

## Core Components

### MainActivity
- Sets up edge-to-edge display
- Configures window insets for status and navigation bars
- Hosts VideoPlayerFragment in fragment_container
- Uses ViewBinding

### VideoPlayerFragment
- Displays YouTube video player
- Shows 3-second countdown before video plays
- Reveals release year after video starts
- Has "Next Video" button to load new video
- Uses findViewById (not ViewBinding)
- Lifecycle-aware with coroutines

### YouTubeSearchService
- Uses YouTube's InnerTube API (no API key required)
- Searches for music videos with 10M+ views
- Falls back to hardcoded list if API fails
- Contains known video ID to year mapping

## Data Classes

### KnownVideo
```kotlin
data class KnownVideo(val id: String, val year: Int)
```
Fallback list of popular music videos with known release years.

### ApiVideo
```kotlin
data class ApiVideo(val id: String, val year: Int, val views: Long, val title: String)
```
Video data from API with view count.

## Key Features

1. **Random Video Selection**: Picks from API results or fallback list
2. **Countdown Timer**: 3-second countdown before video plays
3. **Year Reveal**: Shows release year after video starts
4. **Fallback Mechanism**: Uses hardcoded list if InnerTube API fails
5. **Edge-to-Edge Display**: Full-screen UI with system bar insets

## Build Commands

```bash
./gradlew assembleDebug     # Build debug APK
./gradlew build             # Full build (debug + release)
./gradlew test              # Unit tests
./gradlew connectedCheck    # Instrumentation tests (requires device/emulator)
```

## Release Build
- AAB located at: `app/release/app-release.aab`

## Known Issues / Notes

1. **ViewBinding Inconsistency**: MainActivity and YouTubeFragment use ViewBinding, VideoPlayerFragment uses findViewById
2. **Empty util Package**: AGENTS.md mentions `util/YouTubeApiServiceImpl` and `util/YouTubeAuthManager` but these don't exist
3. **InnerTube API Reliability**: The undocumented YouTube API may not always work; fallback list is used as backup
4. **Year Accuracy**: Some video years in the mapping may be incorrect (e.g., "Never Gonna Give You Up" was released in 1987, not 1987 as stated)