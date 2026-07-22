# GuessTheSongYear - App Structure Overview

## Project Overview
Android app (Kotlin, minSdk 24, targetSdk 37) where users guess the release year of a randomly selected music video. Single-activity architecture with fragment-based game screen.

## Architecture

```
app/src/main/java/com/turbolego/songguesser/
├── MainActivity.kt              # Single activity; hosts fragment, menu, difficulty
├── VideoPlayerFragment.kt       # Core game screen with guessing mechanics
├── YouTubeSearchService.kt      # InnerTube API service for YouTube search
├── ScoreManager.kt              # Scoring system, streaks, accuracy
├── Difficulty.kt                # Difficulty levels enum
├── GameSessionManager.kt        # (Future) Local multiplayer session manager
├── YouTubeModels.kt             # Data models for YouTube API responses
└── util/                        # (Planned) Utility classes
```

## Key Features

1. **🎯 Guess The Year**: Watch a music video snippet → guess its release year
2. **📊 Scoring System**: Points based on accuracy (0 diff = 50pts, up to 10 diff = 5pts)
3. **🔥 Streak System**: Consecutive correct guesses multiply points
4. **🎮 Difficulty Levels**:
   - **Easy** (1980–2010, hints enabled): Decade + view count hints
   - **Medium** (1970–2024): No hints
   - **Hard** (1960–2025): No hints, 2.5x point multiplier
5. **💾 Persistent Fallback**: 38 hardcoded music videos used when API fails
6. **🔍 YouTube InnerTube API**: No API key required (undocumented endpoint)
7. **🌓 Dark Theme**: Material3 dark design with amber accent

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| AndroidX Core KTX | 1.19.0 | Kotlin extensions |
| AppCompat | 1.7.1 | Compatibility |
| Material | 1.14.0 | Material Design |
| ConstraintLayout | 2.2.1 | Layout |
| YouTube Player | 13.0.0 | YouTube playback |
| Kotlinx Coroutines | 1.11.0 | Async |
| OkHttp | 4.12.0 | InnerTube HTTP |

## Build

```bash
./gradlew assembleDebug        # Debug APK (~45MB)
./gradlew assembleRelease      # Release APK (minified, ~12MB)
./gradlew test                 # Unit tests
```

## CI

GitHub Actions workflow under `.github/workflows/build-apk.yml`:
- Builds debug + release APK on push to master
- Runs unit tests
- Uploads artifacts (30-day retention)

## Screens

### Main Activity
- Toolbar with difficulty menu (Easy/Medium/Hard), Reset Score, About
- Fragment container holding VideoPlayerFragment
- Edge-to-edge display

### Video Player Fragment
- YouTube player (16:9)
- Score + streak display bar
- 3-second countdown before video plays
- Hint text (decade + views, easy mode only)
- Year guess input + Submit button
- Feedback text (correct/wrong + points earned)
- Release year reveal
- "Next Video" button

## Game Flow

1. App starts → fetch videos from InnerTube API (or fallback)
2. Random video selected → 3-second countdown
3. Video plays → user guesses year
4. Feedback shown with points earned
5. "Next Video" → repeat

## Known Issues

1. **InnerTube API**: Undocumented YouTube API may change/break. Fallback list used as backup.
2. **Year Accuracy**: Some fallback video years may be approximate (not all have verified release dates).
3. **ViewBinding Inconsistency**: MainActivity uses ViewBinding, VideoPlayerFragment uses ViewBinding. Consistent.
4. **InnerTube Response Parsing**: Multiple JSON path strategies attempted; YouTube may change response structure.
