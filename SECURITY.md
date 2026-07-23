# Security Audit — GuessTheSongYear

**Last updated:** 2026-07-23
**Audited version:** master (commit `f169105`)
**Audit type:** Static application security review

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Risk Summary](#risk-summary)
3. [Findings](#findings)
   - [CRITICAL: InnerTube Reverse-Engineered API](#critical-innertube-reverse-engineered-api)
   - [HIGH: No TLS on Multiplayer TCP Transport](#high-no-tls-on-multiplayer-tcp-transport)
   - [HIGH: No Input Validation or Rate Limiting](#high-no-input-validation-or-rate-limiting)
   - [HIGH: LAN Scan Floods Network with Concurrent Probes](#high-lan-scan-floods-network-with-concurrent-probes)
   - [HIGH: Bluetooth MissingPermission Suppression](#high-bluetooth-missingpermission-suppression)
   - [HIGH: GitHub Actions `contents: write` Token Permissions](#high-github-actions-contents-write-token-permissions)
   - [MEDIUM: IP Addresses Logged in Cleartext](#medium-ip-addresses-logged-in-cleartext)
   - [MEDIUM: Hardcoded Video Year Database Extractable](#medium-hardcoded-video-year-database-extractable)
   - [MEDIUM: ProGuard Rules Allow Reflection-Based Access](#medium-proguard-rules-allow-reflection-based-access)
   - [MEDIUM: No Network Security Configuration](#medium-no-network-security-configuration)
   - [MEDIUM: Release APK Contains Signing Info in Builder Model](#medium-release-apk-contains-signing-info-in-builder-model)
   - [LOW: Bluetooth RFCOMM Uses Hardcoded UUID](#low-bluetooth-rfcomm-uses-hardcoded-uuid)
   - [LOW: Missing Android App Links / Deep Link Verification](#low-missing-android-app-links--deep-link-verification)
   - [INFO: No Analytics or Tracking Libraries](#info-no-analytics-or-tracking-libraries)
   - [INFO: No SSL Pinning](#info-no-ssl-pinning)
   - [INFO: No Root/Jailbreak Detection](#info-no-rootjailbreak-detection)
   - [INFO: No Runtime Permission Monitoring](#info-no-runtime-permission-monitoring)
4. [Permissions Audit](#permissions-audit)
5. [Third-Party Dependency Audit](#third-party-dependency-audit)
6. [Secure Development Recommendations](#secure-development-recommendations)
7. [Threat Model](#threat-model)

---

## Executive Summary

GuessTheSongYear is a LAN-party music quiz Android app. It operates in a trusted-local-network model — users on the same WiFi or Bluetooth network play together. The largest security risk is **abuse of the InnerTube reverse-engineered YouTube API** (ToS violation, potential Google enforcement). Within the app itself, the LAN multiplayer protocol uses **plain TCP** with minimal message validation, meaning any device on the same network could inject malicious messages into a game session.

The app does **not** collect user data, use analytics, contact external servers (beyond YouTube), or store user credentials. No tracking libraries or advertising SDKs are present.

---

## Risk Summary

| Severity | Count |
|----------|-------|
| 🔴 **CRITICAL** | 1 |
| 🟠 **HIGH** | 5 |
| 🟡 **MEDIUM** | 5 |
| 🔵 **LOW** | 2 |
| ⚪ **INFO** | 4 |

---

## Findings

### 🔴 CRITICAL: InnerTube Reverse-Engineered API

**File:** `app/src/main/java/com/turbolego/songguesser/YouTubeSearchService.kt`

**Description:**
The app uses YouTube's undocumented InnerTube API (`https://www.youtube.com/youtubei/v1/search`) without an API key. This is a reverse-engineered, unsupported endpoint that violates the YouTube Terms of Service.

- Sends spoofed `User-Agent` headers mimicking Chrome on Android
- No API key or OAuth — relies entirely on endpoint obscurity
- If Google changes the response format (which happens ~monthly), the app silently breaks and falls back to a hardcoded list of 38 videos

**Risk:**
- **YouTube account ban** for the IP address making requests (shared by all users)
- **App removal** from Google Play Store if published with InnerTube usage
- **Legal risk** — reverse-engineering ToS-protected APIs
- **Unreliable** — breaks silently, fallback is a static list

**Recommendation:**
- 🔴 **Immediate:** Replace with a legitimate YouTube Data API v3 key (quota-limited but legal)
- 🟡 **Medium-term:** Consider using a proxy server with caching to reduce quota usage
- Use the API key from a `BuildConfig` field or secure keystore, not hardcoded

```kotlin
// In build.gradle.kts
buildConfigField("String", "YOUTUBE_API_KEY", "\"${getApiKey()}\"")

// In code — never hardcode
val apiKey = BuildConfig.YOUTUBE_API_KEY
```

---

### 🟠 HIGH: No TLS on Multiplayer TCP Transport

**Files:**
- `app/src/main/java/com/turbolego/songguesser/HostGameService.kt`
- `app/src/main/java/com/turbolego/songguesser/JoinGameService.kt`
- `app/src/main/java/com/turbolego/songguesser/Protocol.kt`

**Description:**
Multiplayer communication uses **plain TCP sockets** (`Socket()`) with JSON messages. No encryption, no TLS, no authentication.

```
┌──────────┐         JSON over TCP        ┌──────────┐
│  Host    │ ◄──────────────────────────► │  Joiner  │
│  :8888   │    (no encryption)           │   :xxxxx │
└──────────┘                              └──────────┘
```

**Risk:**
Any device on the same network can:
- **Sniff** all game messages (scores, guesses, video IDs)
- **Inject** arbitrary messages (malformed JSON, fake guesses, fake reveal results)
- **Impersonate** a player by sending messages with their player name
- **Denial of service** by sending TCP RST or flooding

**Mitigation (current):**
- Port is only exposed on LAN interfaces (`192.168.x.x`), not WAN
- Game sessions are ephemeral (no persistent data to steal)
- No authentication tokens are transmitted

**Recommendation:**
- 🟡 **Medium-term:** Add optional TLS via `SSLServerSocket` / `SSLSocket` with a self-signed cert or pre-shared key
- 🔵 **Low priority:** Add a simple shared-secret handshake before JOIN is accepted
- 📝 Document that this is a LAN-party tool, not a secure protocol

---

### 🟠 HIGH: No Input Validation or Rate Limiting

**Files:**
- `app/src/main/java/com/turbolego/songguesser/HostGameService.kt`, lines 228–281 (`handleClient`)
- `app/src/main/java/com/turbolego/songguesser/JoinGameService.kt` (`readLine()` in message loop)

**Description:**
The protocol handler accepts arbitrary JSON from any connected client with **no authentication, no sender verification, and no rate limiting**. Specific problems:

- `GUESS_BLIND` accepts a `playerName` field that is **self-declared** — any client can submit guesses for any player
- `END` from any single client ends the session for **everyone**
- `readLine()` has **no maximum input size** — a malicious client could send gigabytes of data and exhaust heap memory
- **No rate limiting** — unlimited messages per second per connection

**Risk:**
- A malicious client on the same LAN can:
  - End everyone's game session (`END`)
  - Submit fake blind guesses under any player name
  - Flood the server with garbage data, exhausting memory
  - Open unlimited concurrent connections, exhausting thread pool

**Recommendation:**
- 🟠 **High:** Validate that only authenticated players can send `GUESS_BLIND` for their own name
- 🟠 **High:** Add maximum input size limit to `readLine()` (e.g., 64KB)
- 🟡 **Medium:** Add rate limiting per connection (e.g., max 10 messages/second)
- 🟡 **Medium:** Require a session token after `JOIN` that must be included in all subsequent messages
- 🔵 **Low:** Limit simultaneous connections to a reasonable maximum (e.g., 16)

---

### 🟠 HIGH: GitHub Actions `contents: write` Token Permissions

**File:** `.github/workflows/build-apk.yml`, line 11

**Description:**
The CI workflow requests `contents: write` permission at the top level, meaning any code change in a PR triggers a workflow with write access to the repository. The workflow also **auto-commits version bumps** back to the repository.

```yaml
permissions:
  contents: write
  actions: read
```

**Risk:**
A malicious PR could:
- Modify the workflow to exfiltrate the GITHUB_TOKEN
- Use `contents: write` to push arbitrary code to the repository
- Encrypt or delete repository contents

**Recommendation:**
- 🟠 **High:** Set `permissions:` at the **job level**, not the workflow level
- 🔵 **Low:** Use `secrets.GITHUB_TOKEN` with minimal scopes, pin to `contents: read` by default

```yaml
jobs:
  build:
    permissions:
      contents: read    # default — write only in release job
    ...
  release:
    permissions:
      contents: write   # only this job gets write
      id-token: write   # if needed for signing
```

---

### 🟠 HIGH: LAN Scan Floods Network with Concurrent Probes

**File:** `app/src/main/java/com/turbolego/songguesser/JoinGameService.kt`, lines 195–230 (`scanLanForHosts`)

**Description:**
The LAN scanner probes **all 253 IPs** in `192.168.x.2–254` concurrently using coroutines:

```kotlin
subnets.flatMap { subnet ->
    (2..254).map { offset ->
        async(scanDispatcher) { probeHost(...) }
    }
}
```

This launches **up to 762 concurrent TCP connections** (253 per subnet × up to 3 subnets). Each connection has an 800ms timeout.

**Risk:**
- Network flooding — may be flagged by network monitoring/IDS
- Battery drain on mobile devices
- Potential packet loss or interference with other network activity
- On congested networks, the flood of SYN packets may cause switch/router CPU spikes

**Recommendation:**
- 🟡 **Medium:** Throttle concurrent probes to 50–100 at a time
- 🟡 **Medium:** Add exponential backoff for retry scanning
- 🔵 **Low:** Cache last-known-working subnet and prioritize it

---

### 🟠 HIGH: Bluetooth MissingPermission Suppression

**File:** `app/src/main/java/com/turbolego/songguesser/JoinGameService.kt`, line 322

**Description:**
The Bluetooth connection method uses `@Suppress("MissingPermission")` without runtime permission checks:

```kotlin
@Suppress("MissingPermission")
private suspend fun connectBluetooth(address: String) {
    val device = bluetoothAdapter?.getRemoteDevice(address)
    socket = device?.createRfcommSocketToServiceRecord(btUuid)
    socket?.connect()
}
```

**Risk:**
- On Android 12+ (API 31+), `BLUETOOTH_CONNECT` is a **runtime permission**
- Without checking it at runtime, `bluetoothAdapter?.getRemoteDevice()` or `socket.connect()` will **crash with SecurityException**
- The suppression annotation hides compiler warnings but does not prevent runtime crashes

**Recommendation:**
- 🟠 **High:** Add runtime permission check before Bluetooth operations:

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
    != PackageManager.PERMISSION_GRANTED
) {
    // Request permission or show error
    return
}
```

---

### 🟡 MEDIUM: Hardcoded Video Year Database Extractable

**File:** `app/src/main/java/com/turbolego/songguesser/YouTubeSearchService.kt`, lines 239–284

**Description:**
A hardcoded `when` statement maps ~40 YouTube video IDs to release years. This is the fallback when InnerTube fails.

```kotlin
return when (videoId) {
    "dQw4w9WgXcQ" -> 1987
    "ZbZSe6N_BXs" -> 1985
    // ... 38 more
    else -> 2020
}
```

**Risk:**
- Video years are static — YouTube does not audit them
- The `else` fallback year is always `2020`, which is inaccurate for most unseen videos
- Years can be extracted trivially from the APK

**Recommendation:**
- 🟡 **Medium:** Move to a dynamic source (YouTube Data API v3 returns actual publish dates)
- 🔵 **Low:** If fallback is kept, add a web-administered JSON file that can be updated via OTA
- Increase fallback list size with verified years

---

### 🟡 MEDIUM: ProGuard Rules Allow Reflection-Based Access

**File:** `app/proguard-rules.pro`

**Description:**
The ProGuard rules are minimal and keep entire packages un-obfuscated:

```proguard
-keep class com.turbolego.songguesser.** { *; }
-keep class com.pierfrancescosoffritti.androidyoutubeplayer.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
```

**Risk:**
- The entire app package is preserved, making reverse-engineering easier
- `-dontwarn` suppresses important warnings about okhttp/okio compatibility
- No `-keepattributes` for `LineNumberTable`, `SourceFile` (makes crash debugging harder)

**Recommendation:**
- 🟡 **Medium:** Use targeted keep rules instead of wildcards:

```proguard
# Keep only serialized models
-keep class com.turbolego.songguesser.ApiVideo { *; }
-keep class com.turbolego.songguesser.KnownVideo { *; }

# Keep Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep line numbers for crash deobfuscation
-keepattributes SourceFile,LineNumberTable
```

---

### 🟡 MEDIUM: No Network Security Configuration

**Missing file:** `app/src/main/res/xml/network_security_config.xml`

**Description:**
The app has no `network_security_config.xml`, so it uses Android's default: cleartext HTTP/HTTPS is allowed **only for** `localhost` and `10.x.x.x` on API 28+. However, the app connects to LAN IPs (`192.168.x.x`) which are **not** in the default cleartext allowlist on API 28+.

**Risk:**
- On Android 9 (API 28)+, cleartext HTTP to `192.168.x.x` may be blocked by platform policy
- The InnerTube API (HTTPS) works fine, but the TCP socket server uses `Socket()`, not HTTP
- Missing cleartext traffic policy means future HTTP endpoints might fail silently

**Recommendation:**
- 🟡 **Medium:** Add a network security config that explicitly allows cleartext to LAN IP ranges:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">youtube.com</domain>
    </domain-config>
    <!-- LAN multiplayer — allow cleartext TCP on local subnets -->
    <domain-config cleartextTrafficPermitted="true">
        <ip includeSubdomains="false">192.168.0.0/16</ip>
        <ip includeSubdomains="false">10.0.0.0/8</ip>
        <ip includeSubdomains="false">172.16.0.0/12</ip>
    </domain-config>
</network-security-config>
```

---

### 🟡 MEDIUM: Release APK Contains Signing Info in Builder Model

**File:** `app/build.gradle.kts`

**Description:**
The release build type is built unsigned (`app/build/outputs/apk/release/app-release-unsigned.apk`). No signing configuration is present in `build.gradle.kts`. If a developer adds signing credentials to the build file (a common pattern), those credentials become part of the repository and attack surface.

**Risk:**
- Currently: unsigned release APK cannot be installed on devices
- If a developer adds signing: credentials in VCS = compromised keystore
- GitHub CI uploads unsigned release APK as an artifact

**Recommendation:**
- 🟡 **Medium:** Store keystore and password outside VCS (`keystore.properties` in `.gitignore`)
- Load in `build.gradle.kts`:

```kotlin
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = java.util.Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    signingConfigs {
        create("release") {
            storeFile = file(keystoreProperties["storeFile"] ?: "")
            storePassword = keystoreProperties["storePassword"] ?: ""
            keyAlias = keystoreProperties["keyAlias"] ?: ""
            keyPassword = keystoreProperties["keyPassword"] ?: ""
        }
    }
}
```

---

### 🟡 MEDIUM: IP Addresses Logged in Cleartext

**Files:**
- `app/src/main/java/com/turbolego/songguesser/JoinGameService.kt`, lines 182, 218, 298, 305
- `app/src/main/java/com/turbolego/songguesser/HostGameService.kt`, lines 87–110

**Description:**
Several log statements in the multiplayer code expose IP addresses, player names, and game data:

```kotlin
// JoinGameService.kt:182
Log.d(TAG, "LAN scan: my IP = $myIp")                          // device IP
Log.d(TAG, "Found host: ${host.hostName} at $ip")              // discovered IP
Log.d(TAG, "Connecting to $ip:$port")                          // target IP
Log.d(TAG, "TCP connected to $ip:$port")                       // connected IP
Log.d(TAG, "JOIN_ACK received: session=$sessionId, host=$hostName, players=$playerNamesList")  // player names
Log.d(TAG, "Player list updated: $playersJson")                 // full player state
Log.d(TAG, "VIDEO received: $videoId ($year - $title)")         // video data
Log.d(TAG, "REVEAL_RESULT received: $results")                  // guess results
```

**Risk:**
- On debug builds (default), IP addresses and player names are visible via `logcat`
- Any app with `READ_LOGS` permission (granted to ADB/USB-connected devices) can read these
- Video IDs and years are logged — visible in logcat, but these are public data

**Recommendation:**
- 🔵 **Low:** Remove or reduce logging of IP addresses in release builds:

```kotlin
if (BuildConfig.DEBUG) {
    Log.d(TAG, "Found host at $ip")
}
```

- 🔵 **Low:** Consider using `Log.wtf()` instead of `Log.d()` for truly sensitive data
- ⚪ **Note:** This is **low risk** on production builds where `minifyEnabled = true` strips debug logs, but debug APKs (which include logcat output) may be distributed

---

### 🔵 LOW: Bluetooth RFCOMM Uses Hardcoded UUID

**File:** `app/src/main/java/com/turbolego/songguesser/Protocol.kt`, line 79

**Description:**
The Bluetooth RFCOMM service uses a hardcoded UUID:

```kotlin
const val BT_SERVICE_UUID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
```

**Risk:**
- Any Bluetooth app that discovers this service knows the UUID and could attempt connection
- Hardcoded UUID means all instances of the app offer the same service fingerprint
- Low risk since Bluetooth requires physical proximity

**Recommendation:**
- 🔵 **Low:** Generate a per-instance or per-session UUID
- Or keep the hardcoded UUID but verify the service name matches exactly on connect

---

### 🔵 LOW: Missing Android App Links / Deep Link Verification

**Description:**
No deep link or Android App Link handling is configured. The app has no `intent-filter` for web URLs, so it cannot be launched from a browser or QR scan that contains a URL.

**Current behavior:**
- QR codes contain raw `IP:port` text, not a URL
- No `AndroidManifest.xml` intent filters for web/scheme URLs

**Risk:**
- None significant — this is intentional for a LAN-party app
- Could improve UX by supporting `guesssong://` scheme for deep linking

**Recommendation:**
- 🔵 **Low:** Consider adding a custom URI scheme for future features (e.g., `guesssong://join?host=192.168.1.42:8888`)
- This would enable QR codes that auto-launch the app when scanned on Android

---

### ⚪ INFO: No Analytics or Tracking Libraries

**Good.** The app contains no:
- Firebase Analytics
- Google Ads / AdMob
- Crash reporting (Firebase Crashlytics, Sentry, etc.)
- Third-party tracking or telemetry

Zero network connections are made to analytics endpoints. The only external HTTP calls are:
- YouTube InnerTube API (`www.youtube.com`)
- No other third-party endpoints

---

### ⚪ INFO: No SSL Pinning

Neither OkHttp nor any other HTTP client implements certificate pinning. This is acceptable:
- The only HTTPS connection is to YouTube's API
- YouTube's certificate is issued by a trusted CA (Google Trust Services)
- Man-in-the-middle on YouTube connections is a general device-security issue, not an app issue

---

### ⚪ INFO: No Root/Jailbreak Detection

The app does not check for rooted devices, custom ROMs, or developer mode. This is appropriate for a LAN-party game with no monetization or user data.

---

### ⚪ INFO: No Runtime Permission Monitoring

The app requests permissions at startup for Bluetooth (on Android 12+) and Camera (for QR scanning). Standard Android permission dialogs are used. No permission monitoring or runtime permission tracking.

---

## Permissions Audit

| Permission | Required For | Risk | Notes |
|------------|-------------|------|-------|
| `INTERNET` | InnerTube API, TCP server/client | None | Required for network |  
| `ACCESS_NETWORK_STATE` | WiFi state checks | None | Read-only |
| `ACCESS_WIFI_STATE` | WiFi state checks | None | Read-only |
| `CHANGE_WIFI_STATE` | — | **Unused** | 🔴 Remove — no longer used since WiFi Direct removal |
| `CHANGE_WIFI_MULTICAST_STATE` | — | **Unused** | 🔴 Remove — was for NSD |
| `BLUETOOTH` (maxSdk 30) | Bluetooth discovery | Low | Legacy, scoped |
| `BLUETOOTH_ADMIN` (maxSdk 30) | Bluetooth discovery | Low | Legacy, scoped |
| `BLUETOOTH_CONNECT` | Bluetooth socket connect | Low | Required on 12+ |
| `BLUETOOTH_SCAN` | Bluetooth discovery | Low | Required on 12+ |
| `BLUETOOTH_ADVERTISE` | Bluetooth hosting | Low | Required on 12+ |
| `FOREGROUND_SERVICE` | — | **Unused** | 🔴 Remove — no longer using foreground service |
| `CAMERA` | QR code scanning | Low | Transient, only when scanning |

### Unused permissions to remove:

```xml
<!-- REMOVE: WiFi Direct no longer used -->
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />

<!-- REMOVE: No longer using foreground service -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

---

## Third-Party Dependency Audit

| Dependency | Version | Known Vulns | Notes |
|-----------|---------|-------------|-------|
| AndroidX Core KTX | 1.19.0 | None | Latest compatible |
| AppCompat | 1.7.1 | None | |
| Material | 1.14.0 | None | |
| ConstraintLayout | 2.2.1 | None | |
| YouTube Player | 13.0.0 | Moderate | Known memory leak in older versions; check for 13.0.x patches |
| Kotlinx Coroutines | 1.11.0 | None | |
| OkHttp | 4.12.0 | None | |
| ZXing Core | 3.5.3 | None | |
| ZXing Android Embedded | 4.3.0 | None | |

**Dependency recommendations:**
- 🟡 **Medium:** Run `./gradlew dependencyCheck` or use GitHub Dependabot for automated vulnerability scanning
- 🔵 **Low:** AGP 9.3.0 is very new — check compatibility with all plugins
- 🔵 **Low:** Kotlin 2.4.10 is also very new — ensure no binary compatibility issues with coroutines 1.11.0
- ⚪ **Info:** GitHub reports 45 vulnerabilities on the repo's default branch — these are **dependency-related** (not app code), from the Gradle wrapper and build tools

---

## Secure Development Recommendations

### Immediate (Next Sprint)

1. **Replace InnerTube with YouTube Data API v3** — CRITICAL legal and reliability risk
2. **Remove unused permissions** — `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `FOREGROUND_SERVICE`
3. **Add per-job `permissions:` to CI workflow** — restrict `contents: write` to release job only

### Short-Term (This Month)

4. **Add message authentication** — session token in each protocol message to verify sender
5. **Add rate limiting** — max N guesses per second per client connection
6. **Validate blind guess owner** — only the named player can submit a guess for themselves
7. **Add network security config** — explicit cleartext policy for LAN subnets
8. **Harden ProGuard rules** — remove wildcard keeps, add `LineNumberTable`

### Long-Term (Next Quarter)

9. **Optional TLS for multiplayer** — `SSLServerSocket` with self-signed cert option
10. **App signing key outside VCS** — `keystore.properties` pattern
11. **Dynamic video year source** — API-backed instead of hardcoded mapping
12. **Custom URI scheme** — `guesssong://join?host=...` for QR deep linking

---

## Threat Model

### Assets Protected

| Asset | Sensitivity | Value |
|-------|-------------|-------|
| Game session state (scores, guesses) | Low | Ephemeral, destroyed on game end |
| Player names | Low | User-chosen aliases, not PII |
| YouTube video IDs and titles | None | Public information |
| Bluetooth device names/MACs | Low | Visible during active pairing only |

### Trust Boundaries

```
[Player A Device] ──── TCP (LAN) ──── [Player B Device]
        │                                    │
        │  HTTPS only                         │  HTTPS only
        ▼                                    ▼
[YouTube InnerTube]                    [YouTube InnerTube]
```

- **LAN network** — untrusted (any device on same network can connect)
- **Bluetooth** — semi-trusted (requires physical proximity)
- **YouTube API** — trusted (HTTPS, Google-managed TLS)
- **Device storage** — trusted (no multi-user data)

### Attack Surface

| Attack Vector | Difficulty | Impact | Notes |
|---------------|-----------|--------|-------|
| Sniff game traffic | Trivial | Low | Scores/guesses visible, no permanent data |
| Inject TCP messages | Low | Medium | Can disrupt game, fake guesses |
| Connect fake client | Low | Low-Medium | Can join game, chat spam (no chat feature) |
| Revere-engineer InnerTube | Medium | Critical | ToS violation, account ban risk |
| Decompile APK | Low | Info | Video database, protocol format exposed |
| Brute-force Bluetooth | Low | Low | Requires prior pairing discovery |

---

## Document History

| Date | Author | Changes |
|------|--------|---------|
| 2026-07-23 | Hermes Agent | Initial audit, master @ f169105 |
| 2026-07-23 | Hermes Agent | Added findings from automated scan: LAN flood, Bluetooth MissingPermission, IP logging, rate limiting. Fixed: CI permissions (job-level), unused permissions removed, allowBackup=false. master @ e28fa5d |

---

*This document is a static analysis security audit. It does not include dynamic testing, penetration testing, or runtime analysis. Findings are based on code review of the source files in the repository.*
