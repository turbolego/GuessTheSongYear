# GuessTheSongYear — AGENTS.md

## Branch: master
## Stack: Kotlin, Android (minSdk 24, targetSdk 37), Material3, InnerTube API

This is a music-quiz Android app. Users watch a YouTube music video and guess its release year.

## Multiplayer Architecture

The app supports LAN (local area network) multiplayer:

- **TCP server on port 8888** — Host starts a TCP server on `192.168.x.x:8888`
- **Automatic LAN discovery** — Joiners probe all `192.168.x.2–254` with HELLO/ACK handshake
- **QR code fallback** — Host shows QR with IP:port for scanning
- **Manual IP entry** — Available as fallback

Discovery happens automatically when joiners open the "Join" tab. No manual IP entry needed.

## Build commands

```bash
./gradlew assembleDebug        # Debug APK
./gradlew assembleRelease      # Release APK (requires signing)
./gradlew test                 # Unit tests only
```

## CI

`.github/workflows/build-apk.yml` — builds on push/PR to master.
⚠️ **Security:** CI uses `contents: write` at workflow level — should be moved to job level.

## Dependencies (version catalog)

`gradle/libs.versions.toml`:
- compileSdk=37, minSdk=24, targetSdk=37
- AGP 9.3.0, Kotlin 2.4.10
- YouTube Player 13.0.0 (com.pierfrancescosoffritti.androidyoutubeplayer)
- ViewBinding enabled
- OkHttp 4.12.0 (for InnerTube API)
- ZXing 3.5.3 (QR code generation + scanning)

## Release signed APK

Unsigned release AAB: `app/build/outputs/bundle/release/app-release.aab`
Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
Unsigned release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`

## Key source files

| File | Purpose |
|------|---------|
| `MainActivity.kt` | Activity, toolbar, menu, difficulty switching, QR result forwarding |
| `VideoPlayerFragment.kt` | Core game: player, guess input, scoring |
| `YouTubeSearchService.kt` | InnerTube API (⚠️ reverse-engineered, ToS risk) |
| `ScoreManager.kt` | Points, streaks, accuracy |
| `Difficulty.kt` | Easy/Medium/Hard config |
| `GameSessionManager.kt` | Multiplayer session management (TCP via HostGameService/JoinGameService) |
| `Protocol.kt` | TCP message types, port, Bluetooth UUID |
| `HostGameService.kt` | TCP server — accepts clients, broadcasts messages, LAN discovery ACK |
| `JoinGameService.kt` | TCP client — connects to host, LAN scanner (HELLO probes) |
| `HostGameFragment.kt` | Host UI — IP display, QR code generation, player list |
| `JoinGameFragment.kt` | Join UI — LAN scan results, QR scanning, manual IP entry |

## Security

See [`SECURITY.md`](./SECURITY.md) for full audit. Key issues:
1. 🚨 **InnerTube API** — reverse-engineered YouTube API, ToS violation
2. 🟠 **No TLS** — multiplayer messages in cleartext
3. 🟠 **Minimal protocol validation** — no auth tokens in messages
4. 🟠 **CI token permissions** — `contents: write` too broad
