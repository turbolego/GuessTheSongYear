# Wi-Fi + Bluetooth Multiplayer Plan

> **For Hermes:** Implement task-by-task; commit after each task.

**Goal:** Full network multiplayer over Wi-Fi Direct and Bluetooth RFCOMM. Host picks transport. Each player has their own NumberPicker, guesses secretly, and "Vis svar" reveals all simultaneously. The host only sees picks after reveal.

**Architecture:** Relay-based — host acts as server (WiFi P2P GO / Bluetooth server), clients connect and send encrypted guesses. Host broadcasts video+year at reveal. The existing `HostGameService` and `JoinGameService` heavily rewritten to use a lean pickle/JSON protocol. Local MP uses existing adapter.

**Tech Stack:** Kotlin, Android SDK (WiFi Direct + NSD, Bluetooth RFCOMM), coroutines, JSON protocol.

---

## Phase 1 — Host UI: Transport Picking

### Task 1: Rewrite host UI screen (`fragment_host_game.xml`)

**What:** New layout with "Wi-Fi" / "Bluetooth" picker cards, player lobby, start game button.
**Files:**
- Modify: `app/src/main/res/layout/fragment_host_game.xml`

```xml
<LinearLayout ...>
  <TextView "Velg tilkobling:" />
  <Button id=buttonHostWifi "Wi-Fi" />
  <Button id=buttonHostBluetooth "Bluetooth" />
  <ProgressBar id=progressHost />
  <TextView id=textStatus />
  <RecyclerView id=listJoinedPlayers />
  <Button id=buttonStartGame "Start spill" visibility=gone />
</LinearLayout>
```

### Task 1.2: Create `HostGameFragment.kt`
- **Create:** `app/src/main/java/com/turbolego/songguesser/HostGameFragment.kt`
- Fragment with listeners. Uses `GameSessionManager` for player tracking.
- Starts `HostGameService` with transport choice.

### Task 1.3: Create `JoinGameFragment.kt`
- **Create:** fragment showing discovered hosts (WiFi NSD + Bluetooth), player name input.

---

## Phase 2 — Network Protocol Layer

### Task 2.1: Define protocol messages
- **Modify:** `HostGameService.kt` companion
- New messages: `PRE_INIT`, `GUESS_BLIND`, `REVEAL`, `REVEAL_RESULT`

### Task 2.2: Host GameService — WiFi transport enhanced
- **Modify:** `HostGameService.kt`
- Handle `GUESS_BLIND` (client sends guess, but host **must not** distribute). Host stores in map.
- Handle `REVEAL` event — compute all results, broadcast.
- Broadcast video info + year (on reveal).

### Task 2.3: Host GameService — Bluetooth support
- **Modify:** `HostGameService.kt`
- Add `bluetoothSocket`/`btServerSocket` with RFCOMM listener.
- Same protocol as WiFi.

### Task 2.4: JoinGameService updated
- **Modify:** `JoinGameService.kt`
- Support Bluetooth login via device address.
- Parse `VIDEO`/`REVEAL_RESULT` messages from host.

---

## Phase 3 — UI Side (Game Fragment + MultiPlayerManager)

### Task 3.1: MultiPlayerManager — network remote players
- **Modify:** `MultiPlayerManager.kt`
- Add `isRemotePlayer()`, store per-player guess received from server.
- `recordRemoteGuess(name, guessYear)` – for blind guesses.

### Task 3.2: Adapt `PlayerGuessAdapter` for blind mode
- **Modify:** `PlayerGuessAdapter.kt` — support a mode where guesses are invisible until reveal.
- Add `remoteResultDisplay` field.

### Task 3.3: VideoPlayerFragment — network network
- **Modify:** `VideoPlayerFragment.kt`
- Per-mode: `GameMode.LOCAL`, `GameMode.HOST_WIFI`, `GameMode.HOST_BT`, `GameMode.CLIENT`
- Client: enter year, but no "Vis svar" button (host button only). Only the host's `buttonRevealAnswers` calls `revealAnswers()`.

### Task 3.4: Home screen routing
- **Modify:** `MainActivity.kt` — host screen.

### Task 3.5: Show year in "Neste video" button
- Merge `textViewSongYear` content into buttonNextVideo caption:
  `"2020 — Neste video"`

---

## Phase 4 — Integration & Tests

### Task 4.1: Build APK, manual test flow

### Task 4.2: Commit & push

---

*Total files: 7 new/modified*