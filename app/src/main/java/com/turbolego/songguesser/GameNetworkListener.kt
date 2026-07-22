package com.turbolego.songguesser

import com.turbolego.songguesser.GameSessionManager.GameSession

/**
 * Interface for game network events — shared between host and join services.
 * Activities/fragments implement this to react to WiFi Direct multiplayer events.
 */
interface GameNetworkListener {

    /** Called when the local device starts hosting a session. */
    fun onHostingStarted(sessionId: String, hostName: String)

    /** Called when NSD service registration completes (host) or a host is discovered (joiner). */
    fun onServiceRegistered(serviceName: String)

    /** Called when the local device successfully joins a remote session. */
    fun onJoinedSession(session: GameSession)

    /** A remote player has joined the session. */
    fun onPlayerJoined(playerName: String, clientIp: String)

    /** A remote player disconnected from the session. */
    fun onPlayerDisconnected(playerName: String)

    /** Received a video-change broadcast from the host. */
    fun onVideoReceived(videoId: String, year: Int, title: String)

    /** Received a turn-change broadcast from the host. */
    fun onTurnReceived(playerName: String)

    /** Received a guess result update. */
    fun onGuessReceived(playerName: String, guess: Int, correctYear: Int, score: Int)

    /** Host sent an END message — session is over. */
    fun onSessionEnded()

    /** Network error occurred. */
    fun onNetworkError(error: String)
}