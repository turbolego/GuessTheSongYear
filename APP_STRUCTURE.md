# GuessTheSongYear - App Structure Overview

## Project Overview
Android app (Kotlin, minSdk 24, targetSdk 37) where users guess the release year of a randomly selected music video. Single-activity architecture with fragment-based game screen.

## Architecture

```
app/src/main/java/com/turbolego/songguesser/
├── MainActivity.kt              # Single activity; hosts fragment, menu, difficulty, QR result forwarding
├── VideoPlayerFragment.kt       # Core game: WebView-based YouTube Iframe player, JS bridge, scoring
├── ScoreManager.kt              # Scoring system, streaks, accuracy
├── Difficulty.kt                # Difficulty levels enum
├── GameSessionManager.kt        # Multiplayer session state management
├── Protocol.kt                  # TCP message types, port (8888), Bluetooth UUID
├── HostGameService.kt           # TCP server — host game session, accepts client connections
├── JoinGameService.kt           # TCP client — connect to host, LAN automatic discovery (HELLO/ACK)
├── HostGameFragment.kt          # Host UI: IP display, QR code generation, player list
├── JoinGameFragment.kt          # Join UI: LAN scan results, QR scanning, manual IP entry
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
5. **🌐 LAN Multiplayer** (2 modes):
   - **Host**: TCP server on port 8888, shows IP + QR code, players connect and play together
   - **Join**: Auto-scans LAN for hosts (HELLO/ACK on all 192.168.x.2–254), QR scanning fallback, manual IP entry fallback
6. **📺 YouTube Playback**: WebView + official YouTube Iframe Player API — no keys, ToS-compliant
7. **🌓 Dark Theme**
8. **📷 QR Codes**

## Multiplayer Protocol

See [PROTOCOL.md](PROTOCOL.md) for full protocol specification. Quick summary:

| Transport | Port | Discovery | Encryption |
|-----------|------|-----------|------------|
| TCP (WiFi / LAN hotspot) | 8888 | HELLO/ACK LAN scan, QR code | None (cleartext JSON) |
| Bluetooth RFCOMM | UUID-based | Classic Bluetooth discovery | None (hardcoded UUID) |

All communication is JSON over stream, newline-delimited.

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| AndroidX Core KTX | 1.19.0 | Kotlin extensions |
| AppCompat | 1.7.1 | Compatibility |
| Material | 1.14.0 | Material Design |
| ConstraintLayout | 2.2.1 | Layout |
| Kotlinx Coroutines | 1.11.0 | Async |
| ZXing Core | 3.5.3 | QR code generation |
| ZXing Android Embedded | 4.3.0 | QR camera scanning |

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
- Auto-bumps version on every push
- Creates GitHub Release

## Screens

### Main Activity
- Toolbar with difficulty menu (Easy/Medium/Hard), Reset Score, About
- Fragment container hosting game or multiplayer fragments
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

### Host Game Fragment
- Player name input
- "Host via WiFi" / "Stop" buttons
- Live IP:port display (large text)
- QR code (auto-generated from IP:port)
- Player list (incoming connections)

### Join Game Fragment
- Player name input
- Discovered hosts list (auto-scanned, tap to join)
- "Søk etter spill" (refresh scan) button
- QR scan button (fallback)
- Manual IP entry (fallback)

## Game Flow

### Single Player
1. App starts → load curated video pool
2. Random video selected → 3-second countdown
3. Video plays → user guesses year
4. Feedback shown with points earned
5. "Next Video" → repeat

### Multiplayer (Host)
1. Tap "Host via WiFi" → TCP server starts on port 8888
2. IP + QR code shown — share with other players
3. Players join → game session starts
4. Host controls game flow (next video, reveal)

### Multiplayer (Join)
1. Tap "Bli med i spill" → auto-scans LAN for hosts
2. Discovered hosts appear in list → tap one to join
3. Alternative: scan host's QR code or enter IP manually
4. Connected → play in sync with host

## Security

See [SECURITY.md](SECURITY.md) for full audit findings. Critical items:
1. **InnerTube is reverse-engineered YouTube API** — ToS violation, unreliable
2. **No TLS encryption** on LAN multiplayer (cleartext TCP)
3. **Minimal protocol message validation** — no session authentication tokens

## Known Issues

1. **Static video pool**: ~40 curated videos — may repeat after 40+ rounds
2. **Year Accuracy**: Some fallback video years may be approximate (not all have verified release dates).
3. **No TLS**: Multiplayer traffic is visible to anyone on the same network.
4. **No Protection from Malicious Clients**: Any device on the LAN can connect and disrupt gameplay.
5. **InnerTube Response Parsing**: Multiple JSON path strategies attempted; YouTube may change response structure.
6. **Unused Permissions**: `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `FOREGROUND_SERVICE` are declared but no longer needed.
