# GuessTheSongYear — AGENTS.md

## Branch: master
## Stack: Kotlin, Android (minSdk 24, targetSdk 37), Material3, NewPipe + ExoPlayer YouTube player

This is a music-quiz Android app. Users watch a YouTube music video and guess its release year.

**Video source:** Curated hardcoded list of ~40 known music videos. YouTube streams extracted via **NewPipe Extractor v0.26.4**, played with **Media3 ExoPlayer** — no API keys required.

## Multiplayer Architecture

The app supports LAN (local area network) multiplayer:

- **TCP server on port 8888** — Host starts a TCP server on `192.168.x.x:8888`
- **Automatic LAN discovery** — Joiners probe all `192.168.x.2–254` with HELLO/ACK handshake
- **QR code fallback** — Host shows QR with IP:port for scanning
- **Manual IP entry** — Available as fallback

Discovery happens automatically when joiners open the "Join" tab. No manual IP entry needed.

**Same-device local multiplayer** is also supported — `MultiplayerSetupFragment` lets 2+ players add names, `MultiPlayerManager` tracks turns/scores, and `MultiplayerGuessAdapter` shows a `NumberPicker` per player for simultaneous guessing.

## Build commands

```bash
./gradlew assembleDebug        # Debug APK
./gradlew assembleRelease      # Release APK (requires signing)
./gradlew test                 # Unit tests only
```

Test classes: `GameSessionManagerTest`, `VideoPlayerFragmentTest`, `NewPipeRuntimeTest`, `YouTubeEmbeddabilityTest`

## CI

`.github/workflows/build-apk.yml` — builds on push/PR to master. `contents: write` is scoped to job level.

## Dependencies (version catalog)

`gradle/libs.versions.toml`:
- compileSdk=37, minSdk=24, targetSdk=37
- AGP 9.3.0, Kotlin 2.4.10
- ViewBinding enabled
- ZXing 3.5.3 (QR code generation + scanning)
- Media3 (ExoPlayer) 1.9.0 (YouTube stream playback)
- NewPipe Extractor v0.26.4 (YouTube stream URL extraction)
- Kotlinx Coroutines (async stream extraction in `VideoPlayerFragment`)
- **No** standalone YouTube Player library, **no** InnerTube API, **no** OkHttp

## Release signed APK

Unsigned release AAB: `app/build/outputs/bundle/release/app-release.aab`
Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
Unsigned release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`

## Key source files

| File | Purpose |
|------|---------|
| `MainActivity.kt` | Activity, toolbar, menu, difficulty switching, QR result forwarding |
| `VideoPlayerFragment.kt` | Core game: NewPipe stream extraction, Media3 ExoPlayer, guess input, scoring |
| `ScoreManager.kt` | Points, streaks, accuracy |
| `Difficulty.kt` | Easy/Medium/Hard config |
| `GameSessionManager.kt` | Multiplayer session management (TCP via HostGameService/JoinGameService) |
| `Protocol.kt` | TCP message types, port, Bluetooth UUID |
| `HostGameService.kt` | TCP server — accepts clients, broadcasts messages, LAN discovery ACK |
| `JoinGameService.kt` | TCP client — connects to host, LAN scanner (HELLO probes) |
| `HostGameFragment.kt` | Host UI — IP display, QR code generation, player list |
| `JoinGameFragment.kt` | Join UI — LAN scan results, QR scanning, manual IP entry |
| `MultiplayerSetupFragment.kt` | Same-device local multiplayer setup: add/remove players (min 2) |
| `MultiPlayerManager.kt` | Local multiplayer state: players list, guesses, scores, turn tracking |
| `MultiplayerGuessAdapter.kt` | RecyclerView adapter for local multiplayer: NumberPicker per player |
| `GameNetworkListener.kt` | Interface for WiFi/Bluetooth multiplayer network events |
| `DebugLogger.kt` | In-memory log collector (max 200 entries) + logcat output |
| `DebugFragment.kt` | Debug UI for testing NewPipe stream extraction with live logging |

## Security

See [`SECURITY.md`](./SECURITY.md) for full audit. Key issues:
1. ✅ ~~InnerTube API~~ — resolved, replaced with NewPipe Extractor + Media3 ExoPlayer
2. 🟠 **No TLS** — multiplayer messages in cleartext
3. 🟠 **Minimal protocol validation** — no auth tokens in messages
4. ✅ ~~CI token permissions~~ — `contents: write` scoped to job level
