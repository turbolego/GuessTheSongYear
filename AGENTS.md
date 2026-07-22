# GuessTheSongYear — AGENTS.md

## Branch: master
## Stack: Kotlin, Android (minSdk 24, targetSdk 37), Material3, InnerTube API

This is a music-quiz Android app. Users watch a YouTube music video and guess its release year.

## Feature flags / future work

- [ ] **Local Multiplayer**: WiFi P2P with GameSessionManager (stubbed)
- [ ] **High Score Persistence**: SharedPreferences or Room DB to save best scores
- [ ] **Music Video Category Filter**: Genre, decade, artist filters
- [ ] **Leaderboard**: Firebase or local leaderboard
- [ ] **Sound FX**: Guess correct/wrong sounds
- [ ] **Animations**: Smoother transitions between videos

## Build commands

```bash
./gradlew assembleDebug        # Debug APK
./gradlew assembleRelease      # Release APK (requires signing)
./gradlew test                 # Unit tests only
```

## CI

`.github/workflows/build-apk.yml` — builds on push/PR to master.

## Dependencies (version catalog)

`gradle/libs.versions.toml`:
- compileSdk=37, minSdk=24, targetSdk=37
- AGP 9.3.0, Kotlin 2.4.10
- YouTube Player 13.0.0 (com.pierfrancescosoffritti.androidyoutubeplayer)
- ViewBinding enabled

## Release signed APK

Unsigned release AAB: `app/build/outputs/bundle/release/app-release.aab`
Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
Unsigned release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`

## Key source files

| File | Purpose |
|------|---------|
| `MainActivity.kt` | Activity, toolbar, menu, difficulty switching |
| `VideoPlayerFragment.kt` | Core game: player, guess input, scoring |
| `YouTubeSearchService.kt` | InnerTube API, falls back to hardcoded list |
| `ScoreManager.kt` | Points, streaks, accuracy |
| `Difficulty.kt` | Easy/Medium/Hard config |
| `GameSessionManager.kt` | Multiplayer session (stub for future) |
