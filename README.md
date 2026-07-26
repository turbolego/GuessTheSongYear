# GuessTheSongYear 🎵

**A music quiz party game for Android** — watch a music video, guess its release year, compete with friends on the same device or over LAN.

No accounts. No ads. No tracking. Just music and guessing.

---

## 🎮 How the Game Works

1. **Pick a difficulty** — Easy (1980–2010, with hints), Medium (1970–2024), or Hard (1960–2025). Harder = more points.
2. **Watch the video** — A YouTube music video plays and you listen (and watch, unless you toggle listen-only mode).
3. **Guess the year** — Type a year or scroll through the year picker. The closer you are, the more points you get.
4. **See the results** — After everyone guesses, the host reveals the answer with a leaderboard.

**Exact guess:** 50 pts + streak bonus · **Off by 1:** 30 pts · **Off by ≤3:** 20 pts · **Off by ≤5:** 10 pts · **Off by ≤10:** 5 pts · **Way off:** 0 pts, but the right answer stares you in the face.

---

## 👥 Multiplayer

Play solo or host a LAN party — everyone on the same WiFi can join:

| Mode | How it works |
|---|---|
| **Host** | Press "Vert" (Host). The app shows your IP + a QR code. Friends scan or type it in. |
| **Join** | Press "Bli med" (Join). It scans your network automatically for active hosts — no manual typing needed. |
| **QR scan** | Press the QR button to scan the host's code directly with your camera. |

**Discovery is automatic.** As soon as someone opens the Join tab, the app probes `192.168.x.2–254` looking for hosts with a HELLO/ACK handshake. Usually it finds the host before you notice.

### Connection Types

- **WiFi** (TCP on port 8888) — the default, requires same LAN
- **TLS** (TCP on port 8889) — encrypted channel with SPKI certificate pinning, activated when the joiner scans the host's QR code
- **Bluetooth RFCOMM** — close-range fallback, no WiFi needed

Messages exchanged between host and joiners are **HMAC-SHA256 signed** using a per-session random key distributed at join time. Older unsigned clients are still accepted (backward-compatible).

---

## 📱 Features

- 🎵 **YouTube video via official Iframe Player API** — zero ToS violations, no API keys, no user login
- 🎵 **~40 music videos spanning decades** — from 80s classics to modern hits
- 🎯 **Three difficulty levels** with different year ranges and score multipliers
- 📊 **Streak system** — correct guesses build a streak for bonus points, wrong guesses reset it
- 🔊 **Listen-only mode** — the video overlay with the video opens and manages YouTube externally
- 🌓 **Dark theme** with amber accent, Material3 design
- 🔤 **Norwegian ←→ English UI** — switch language from the toolbar
- 🚫 **Duplicate prevention** — songs you've already seen are avoided
- ⏭️ **Auto-skip** — embed-disabled videos skip silently to the next one
- 📱 **Min SDK 24** (Android 7.0) through **target SDK 37** (Android 15)

---

## 🏗️ Architecture

```
GuessTheSongYear
├── MainActivity.kt                Entry point, toolbar, difficulty & language switching
├── VideoPlayerFragment.kt         Core game: WebView YouTube Iframe API, guess engine, scoring
├── ScoreManager.kt                Points calculation, streaks, accuracy
├── Difficulty.kt                  Easy/Medium/Hard config (year ranges + multipliers)
├── GameSessionManager.kt          Session state for multiplayer
├── HostGameFragment.kt            Host UI: IP/QGSR display, player list
├── JoinGameFragment.kt            Join UI: LAN scan results, QR scanning, manual IP entry
├── Protocol.kt                    TCP message JSON protocol (10 message types)
├── HostGameService.kt             TCP server on port 8888 + TLS on 8889, HMAC signing
├── JoinGameService.kt             TCP client, LAN scanner, SPKI pinning, HMAC verify
├── SecureChannelManager.kt        TLS infrastructure: ephemeral RSA keys, self-signed certs
└── ... (layouts, resources, tests)
```

### Multiplayer Protocol

All network messages are newline-delimited JSON over a reliable transport (TCP or Bluetooth RFCOMM).

```
  1. Client → Host:  JOIN {type, player}
  2. Host → Client:  JOIN_ACK {type, sessionId, hostName, sessionKey, players[]}
  3. Host → All:     VIDEO {type, id, year, title, sig}
  4. Host → All:     PLAYER_LIST {type, players[], sig}
  5. Host → All:     REVEAL {type, sig}
  6. Client → Host:  GUESS_BLIND {type, player, guess, sig}
  7. Host → Client:  REVEAL_RESULT {type, results[], leaderboard[], sig}
  8. Host → All:     PLAYER_LEFT {type, player, sig}
  9. Host → All:     END {type, player?, sig}
 10. Joiner → LAN:   HELLO → ACK (discovery handshake)
```

### Security Features

| Layer | Approach |
|---|---|
| **Transport** | Optional TLS (`TLSv1.3`) with ephemeral RSA 2048 keys + self-signed X.509 cert per session |
| **Certificate pinning** | SPKI hash shown on host screen — clients verify it during TLS join |
| **Message signing** | HMAC-SHA256 on every message body, key shared in `JOIN_ACK` |
| **Fallback** | Plain TCP still available for backwards compatibility; unsigned messages still accepted from old clients |
| **CI** | `contents: write` permission scoped to release job only, not workflow level |

---

## 🧱 Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| Build | Gradle + Android Gradle Plugain 9.3.0 + Kotlin 2.4.10 |
| UI | Android ViewBinding + Material3 |
| Video player | YouTube Iframe Player API (WebView embeddings) |
| YouTube API library | `com.pierfrancescosoffritti.androidyoutubeplayer:core` 12.1.2 |
| QR code | ZXing Core 3.5.3 + ZXing Embedded 4.3.0 |
| Crypto (TLS) | BouncyCastle bcpkis-jdk18on 1.84 + standard javax.net.ssl |
| Crypto (signing) | HMAC-SHA256 via javax.crypto.Mac |
| CI/CD | GitHub Actions (build + test + release on push to master) |
| Dependabs | Dependabot for weekly Gradle dependency updates |

---

## 🧪 Testing

```bash
./gradlew test               # Unit tests
./gradlew assembleDebug      # Debug APK
./gradlew assembleRelease    # Release APK (unsigned, requires signing config)
```

**CI workflow:** `perc/.github/workflows/build-apk.yml` runs on push/PR to `master`:
- Builds Debug + Release APKs
- Runs unit tests
- Auto-bumps version code
- Creates a GitHub release with both APKs as artifacts

---

## 📄 License

This project was originally a weekend hack by **Torbjørn "turbolego" Haugen**. The name "GuessTheSongYear", the game loop concept, and all source code in this repository are OSI of Torbjørn Haugen. Contributions interest are welcome through the standard workflow: fork → branch → PR into `master`.

The app should be a drinking game—install some may be already.

---

## 🔐 Security

The game is a LAN party in tool, not a battle-hardened surveillance system. That is understood — and so there has also some security effort gone into the most meaningful attack surfaces:

- All multiplayer messages are **HMAC-SHA256 signed** per session
- Optional **TLS 1.3** + **SPKI certificate pinning** on the network layer
- CI **permissions are scoped** at the job level
- No API keys, no tracking, no analytics, no outbound connections beyond YouTube

For a detailed security audit of every component, see **[SECURITY.md](./SECURITY.md)** (600+ lines of structured findings, threats, and recommendations).

---

## 📦 Releases

Every push to `master` auto-builds a new APK through CI, bumps version numbers, and creates a [GitHub Release](https://github.com/turbulego/GuessTheSongYear/releases). Go find the latest one there and side-load onto your Android device — the project isn't on the Play Stars Store (it wasn't needed setup: it was a weekend side-project and there was no reason to fill in Google's tax registration form).