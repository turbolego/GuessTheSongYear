package com.turbolego.songguesser

import org.json.JSONArray
import org.json.JSONObject

/**
 * Canonical message types and JSON helpers for the GuessTheSongYear multiplayer protocol.
 *
 * All networked messages are newline-delimited JSON (one JSON object per line)
 * over a reliable transport (TCP or Bluetooth RFCOMM).
 *
 * Protocol flow:
 *   1. Client → Host: JOIN {type, player}
 *   2. Host → Client: JOIN_ACK {type, sessionId, hostName, players[], currentVideoId?, currentYear?, currentTitle?}
 *   3. Host → All:  VIDEO {type, id, year, title}  (when a new video loads)
 *   4. Host → All:  PLAYER_LIST {type, players[]}   (scores updated)
 *   5. Host → All:  REVEAL {type}                    (host pressed "Vis svar")
 *   6. Client → Host: GUESS_BLIND {type, player, guess}  (sent after REVEAL)
 *   7. Host → Client: REVEAL_RESULT {type, results[{player, guess, correctYear, pointsEarned, difference, isCorrect}], leaderboard[{name, score}]}
 *   8. Host → All:  PLAYER_LEFT {type, player}
 *   9. Host → All / Client → Host: END {type, player?}
 */
object Protocol {

    // ── Message type constants ───────────────────────────────────────────────

    const val MSG_JOIN = "JOIN"
    const val MSG_JOIN_ACK = "JOIN_ACK"
    const val MSG_PLAYER_LIST = "PLAYER_LIST"
    const val MSG_VIDEO = "VIDEO"
    const val MSG_REVEAL = "REVEAL"
    const val MSG_GUESS_BLIND = "GUESS_BLIND"
    const val MSG_REVEAL_RESULT = "REVEAL_RESULT"
    const val MSG_PLAYER_LEFT = "PLAYER_LEFT"
    const val MSG_END = "END"
    const val MSG_HELLO = "HELLO"
    const val MSG_ACK = "ACK"

    // ── JSON field constants ─────────────────────────────────────────────────

    const val FIELD_TYPE = "type"
    const val FIELD_PLAYER = "player"
    const val FIELD_PLAYERS = "players"
    const val FIELD_SESSION_ID = "sessionId"
    const val FIELD_HOST_NAME = "hostName"
    const val FIELD_VIDEO_ID = "id"
    const val FIELD_YEAR = "year"
    const val FIELD_TITLE = "title"
    const val FIELD_GUESS = "guess"
    const val FIELD_CORRECT_YEAR = "correctYear"
    const val FIELD_POINTS_EARNED = "pointsEarned"
    const val FIELD_DIFFERENCE = "difference"
    const val FIELD_IS_CORRECT = "isCorrect"
    const val FIELD_SCORE = "score"
    const val FIELD_TOTAL_SCORE = "totalScore"
    const val FIELD_NAME = "name"
    const val FIELD_IS_HOST = "isHost"
    const val FIELD_RESULTS = "results"
    const val FIELD_LEADERBOARD = "leaderboard"
    const val FIELD_CURRENT_VIDEO_ID = "currentVideoId"
    const val FIELD_CURRENT_YEAR = "currentYear"
    const val FIELD_CURRENT_TITLE = "currentTitle"
    const val FIELD_CURRENT_PLAYER_INDEX = "currentPlayerIndex"
    const val FIELD_CURRENT_PLAYER = "currentPlayer"
    const val FIELD_PLAYER_NAMES = "playerNames"

    // ── Transport type constants ─────────────────────────────────────────────

    const val TRANSPORT_WIFI = "wifi"
    const val TRANSPORT_BLUETOOTH = "bluetooth"

    // ── Service constants ────────────────────────────────────────────────────

    /** Default TCP server port for Wi-Fi transport. */
    const val WIFI_SERVER_PORT = 8888

    /** Bluetooth RFCOMM service name (used for SDP record). */
    const val BT_SERVICE_NAME = "GuessTheSongYear"

    /** Bluetooth RFCOMM service UUID (random, must match on host + join). */
    const val BT_SERVICE_UUID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"

    // ── Timeouts ──────────────────────────────────────────────────────────────

    const val SOCKET_TIMEOUT_MS = 30_000
    const val CONNECT_TIMEOUT_MS = 15_000
    const val RECONNECT_DELAY_MS = 3_000L
    const val MAX_RECONNECT_ATTEMPTS = 3

    // ── JSON helpers ─────────────────────────────────────────────────────────

    /** Convenience builder for [JSONObject]. */
    inline fun buildJson(block: JSONObject.() -> Unit): JSONObject =
        JSONObject().apply(block)

    /** Convenience builder for [JSONArray]. */
    inline fun buildJsonArray(block: JSONArray.() -> Unit): JSONArray =
        JSONArray().apply(block)

    /**
     * Safely parse a JSON string, returning null on any parse error.
     */
    fun tryParse(json: String): JSONObject? =
        try {
            JSONObject(json)
        } catch (_: Exception) {
            null
        }
}
