# GuessTheSongYear — Multiplayer Protocol

**Version:** 1.0.0
**Last updated:** 2026-07-23
**Transport:** TCP (port 8888) or Bluetooth RFCOMM (UUID: `a1b2c3d4-e5f6-7890-abcd-ef1234567890`)

---

## Overview

The multiplayer system uses a **JSON-over-stream** protocol with newline-delimited messages. Messages between host and players are plain JSON objects serialized to strings, terminated by `\n`.

- **Default port:** 8888
- **Encoding:** UTF-8
- **Message delimiter:** `\n` (newline)
- **Transport security:** None (cleartext — see [SECURITY.md](./SECURITY.md))

---

## Message Types

All messages are JSON objects with a `type` field. Additional fields vary by message type.

| Message | Direction | Description |
|---------|-----------|-------------|
| `HELLO` | Join → Host | LAN discovery probe (automatic scanning) |
| `ACK` | Host → Join | LAN discovery response |
| `JOIN` | Join → Host | Request to join game with player name |
| `JOINED` | Host → Join | Confirmation of join, includes session state |
| `PLAYER_JOINED` | Host → All | Broadcast: new player joined |
| `PLAYER_DISCONNECTED` | Host → All | Broadcast: player left |
| `START` | Host → All | Begin game, includes video info |
| `VIDEO` | Host → All | Next video to play |
| `TURN` | Host → Player | Your turn to guess |
| `GUESS_BLIND` | Join → Host | Submit a blind guess (year) |
| `GUESS_RESULT` | Host → Join | Result of the guess |
| `GUESS_BROADCAST` | Host → All | Broadcast: someone guessed (for reveal phase) |
| `REVEAL` | Host → Join | Reveal the correct year |
| `REVEAL_RESULTS` | Host → All | All players' reveal results |
| `STATE` | Host → All | Game state sync |
| `END` | Host → All / Any → Host | End the game session |
| `ERROR` | Any | Error message |

> **Security note:** `END` and `GUESS_BLIND` lack sender authentication. Any connected client can send these messages. See [SECURITY.md](./SECURITY.md) for details.

---

## Protocol Flow

### 1. LAN Discovery (Automatic)

On the **Join** side, when the join fragment opens, it automatically probes all IPs on the `192.168.x.2–254` subnet:

```
Join → Host:   "HELLO\n"
Host → Join:   "ACK\n"
Host           (closes connection — not a full game join)
```

If a host responds with `ACK`, its IP is added to the discovered hosts list. The joiner can tap to initiate a full connection.

### 2. Connection

```
Join → Host:   {"type": "JOIN", "playerName": "Alice", "playerId": "alice-tag"}
Host → Join:   {
                 "type": "JOINED",
                 "playerId": "alice-tag",
                 "hostName": "Bob's Game",
                 "players": {
                   "bob-tag": {"name": "Bob", "score": 0, "hasGuessedBlind": false},
                   "alice-tag": {"name": "Alice", "score": 0, "hasGuessedBlind": false}
                 },
                 "currentRound": 1,
                 "gameState": "LOBBY"
               }
Host → All:    {"type": "PLAYER_JOINED", "playerName": "Alice", "playerId": "alice-tag"}
```

### 3. Game Loop

```
Host → All:    {"type": "VIDEO", "videoId": "dQw4w9WgXcQ", "round": 1, "totalRounds": 10}
Host → Player: {"type": "TURN", "playerName": "Bob"}
Player → Host: {"type": "GUESS_BLIND", "playerName": "Bob", "guess": 1987}
Host → Player: {"type": "GUESS_RESULT", "correct": true, "points": 50, "year": 1987}
Host → All:    {"type": "GUESS_BROADCAST", "playerName": "Bob", "diff": 0}
               ... (repeat for each player)
Host → Player: {"type": "REVEAL", "year": 1987, "title": "Never Gonna Give You Up"}
Host → All:    {"type": "REVEAL_RESULTS", "results": [
                 {"playerName": "Bob", "score": 50, "guessDiff": 0, "roundScore": 50, "streak": 1},
                 {"playerName": "Alice", "score": 50, "guessDiff": 0, "roundScore": 50, "streak": 1}
               ]}
```

### 4. End Session

```
Host → All:    {"type": "END", "reason": "GAME_OVER"}
             — or —
Any:           {"type": "END"}
```

> **Note:** `END` from any client triggers session end for everyone. This is a known security weakness.

---

## Message Details

### HELLO (LAN Scan Probe)

```json
"HELLO\n"
```

Plain string, not JSON. Sent by the joiner's LAN scanner to probe for active hosts on port 8888.

### ACK (LAN Scan Response)

```json
"ACK\n"
```

Plain string, not JSON. Sent by the host in response to `HELLO`. No game join happens — the connection is closed immediately after ACK.

### JOIN

```json
{
  "type": "JOIN",
  "playerName": "string",
  "playerId": "string (optional, auto-generated if missing)"
}
```

### JOINED

```json
{
  "type": "JOINED",
  "playerId": "string",
  "hostName": "string",
  "players": {
    "playerId": {
      "name": "string",
      "score": 0,
      "hasGuessedBlind": false
    }
  },
  "currentRound": 0,
  "gameState": "LOBBY | GUESSING | REVEAL | FINISHED"
}
```

### PLAYER_JOINED / PLAYER_DISCONNECTED

```json
{
  "type": "PLAYER_JOINED",
  "playerName": "string",
  "playerId": "string",
  "playerCount": 3
}
```

### VIDEO

```json
{
  "type": "VIDEO",
  "videoId": "string (YouTube ID)",
  "round": 1,
  "totalRounds": 10,
  "year": 1987,
  "title": "Song Title"
}
```

**Note:** `year` is sent to all players but should only be used by the host for comparison. Blind-guess protocol means players' clients should ignore `year`.

### TURN

```json
{
  "type": "TURN",
  "playerName": "string",
  "playerId": "string"
}
```

### GUESS_BLIND

```json
{
  "type": "GUESS_BLIND",
  "playerName": "string (self-declared — not verified)",
  "guess": 1987
}
```

> **Security note:** `playerName` is self-declared. A malicious client can impersonate other players by submitting guesses under their name. See [SECURITY.md](./SECURITY.md).

### GUESS_RESULT

```json
{
  "type": "GUESS_RESULT",
  "correct": true,
  "points": 50,
  "year": 1987,
  "diff": 0,
  "totalScore": 150
}
```

### GUESS_BROADCAST

```json
{
  "type": "GUESS_BROADCAST",
  "playerName": "string",
  "playerId": "string",
  "year": 1987,
  "diff": 0,
  "roundScore": 50,
  "streak": 3
}
```

### REVEAL

```json
{
  "type": "REVEAL",
  "year": 1987,
  "title": "Song Title"
}
```

### REVEAL_RESULTS

```json
{
  "type": "REVEAL_RESULTS",
  "results": [
    {
      "playerName": "string",
      "playerId": "string",
      "score": 50,
      "guessDiff": 0,
      "roundScore": 50,
      "streak": 1
    }
  ]
}
```

### STATE (Sync)

```json
{
  "type": "STATE",
  "players": {
    "playerId": { "name": "string", "score": 0 }
  },
  "currentRound": 1,
  "gameState": "LOBBY | GUESSING | REVEAL | FINISHED"
}
```

### END

```json
{
  "type": "END",
  "reason": "GAME_OVER | HOST_DISCONNECTED | MANUAL"
}
```

### ERROR

```json
{
  "type": "ERROR",
  "message": "Human-readable error description"
}
```

---

## Protocol Constants

Defined in `Protocol.kt`:

```kotlin
const val TCP_PORT = 8888
const val MSG_TYPE = "type"
const val MSG_PLAYER_NAME = "playerName"
const val MSG_PLAYER_ID = "playerId"
const val MSG_HOST_NAME = "hostName"
const val MSG_PLAYERS = "players"
const val MSG_CURRENT_ROUND = "currentRound"
const val MSG_TOTAL_ROUNDS = "totalRounds"
const val MSG_GAME_STATE = "gameState"
const val MSG_VIDEO_ID = "videoId"
const val MSG_GUESS = "guess"
const val MSG_CORRECT = "correct"
const val MSG_POINTS = "points"
const val MSG_YEAR = "year"
const val MSG_SCORE = "score"
const val MSG_RESULTS = "results"
const val MSG_REASON = "reason"
const val MSG_PLAYER_COUNT = "playerCount"
const val MSG_GUESS_DIFF = "diff"
const val MSG_ROUND_SCORE = "roundScore"
const val MSG_STREAK = "streak"
const val MSG_HELLO = "HELLO"
const val MSG_ACK = "ACK"
const val MSG_END = "END"

const val VAL_JOIN = "JOIN"
const val VAL_JOINED = "JOINED"
const val VAL_START = "START"
const val VAL_VIDEO = "VIDEO"
const val VAL_TURN = "TURN"
const val VAL_GUESS_BLIND = "GUESS_BLIND"
const val VAL_GUESS_RESULT = "GUESS_RESULT"
const val VAL_GUESS_BROADCAST = "GUESS_BROADCAST"
const val VAL_REVEAL = "REVEAL"
const val VAL_REVEAL_RESULTS = "REVEAL_RESULTS"
const val VAL_PLAYER_JOINED = "PLAYER_JOINED"
const val VAL_PLAYER_DISCONNECTED = "PLAYER_DISCONNECTED"
const val VAL_STATE = "STATE"
const val VAL_ERROR = "ERROR"

// Game states
const val GS_LOBBY = "LOBBY"
const val GS_GUESSING = "GUESSING"
const val GS_REVEAL = "REVEAL"
const val GS_FINISHED = "FINISHED"
```

---

## Security Considerations

⚠️ **See [SECURITY.md](./SECURITY.md) for full audit.** Key protocol-specific issues:

1. **No authentication** — `playerName` in `GUESS_BLIND` is self-declared and not verified
2. **No encryption** — all messages sent in cleartext over TCP
3. **No rate limiting** — any client can flood the server with messages
4. **`END` from any source** — any connected client can end the session for everyone
5. **Bluetooth UUID** — hardcoded and discoverable by any app on nearby devices

---

## Document History

| Date | Author | Changes |
|------|--------|---------|
| 2026-07-23 | Hermes Agent | Initial documentation, master @ f169105 |
