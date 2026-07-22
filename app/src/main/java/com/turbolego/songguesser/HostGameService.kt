package com.turbolego.songguesser

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

/**
 * Android Service that hosts a WiFi Direct + NSD multiplayer game session.
 *
 * Responsibilities:
 * - Creates a WiFi Direct P2P group as the Group Owner
 * - Publishes the game service via NSD (Network Service Discovery)
 * - Accepts incoming TCP socket connections from joined clients
 * - Manages the game protocol (VIDEO, TURN, GUESS, END, JOIN messages)
 * - Syncs video selection, turn info, and scores via JSON over sockets
 * - Integrates with [GameSessionManager] for session state
 */
class HostGameService : Service() {

    // ── Constants ───────────────────────────────────────────────────────────

    private val TAG = "HostGameService"
    private val SERVICE_TYPE = "_guessgame._tcp"
    private val SERVICE_NAME = "GuessTheSongYear"
    private val SERVER_PORT = 8888
    private val SOCKET_TIMEOUT = 30_000
    private val JSON_CHARSET = Charsets.UTF_8

    // ── Message Types ───────────────────────────────────────────────────────

    companion object {
        const val MSG_JOIN = "JOIN"
        const val MSG_JOIN_ACK = "JOIN_ACK"
        const val MSG_PLAYER_LIST = "PLAYER_LIST"
        const val MSG_VIDEO = "VIDEO"
        const val MSG_TURN = "TURN"
        const val MSG_GUESS = "GUESS"
        const val MSG_END = "END"
        const val MSG_PLAYER_LEFT = "PLAYER_LEFT"

        /**
         * Convenience method to start the hosting service.
         * The caller should pass a [playerName] used as the host's display name.
         */
        fun start(context: Context, playerName: String) {
            val intent = Intent(context, HostGameService::class.java).apply {
                putExtra(EXTRA_PLAYER_NAME, playerName)
            }
            context.startForegroundService(intent)
        }

        /**
         * Convenience method to stop the hosting service.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, HostGameService::class.java))
        }

        private const val EXTRA_PLAYER_NAME = "player_name"
    }

    // ── Services & State ────────────────────────────────────────────────────

    private lateinit var wifiP2pManager: WifiP2pManager
    private var wifiChannel: WifiP2pManager.Channel? = null
    private lateinit var nsdManager: NsdManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var acceptJob: Job? = null
    private var serverSocket: ServerSocket? = null

    /** Active client connections keyed by player name. */
    private val clients = ConcurrentHashMap<String, ClientConnection>()

    /** Guards broadcast operations so messages are serialised per socket. */
    private val broadcastMutex = Mutex()

    /** Holds the host's IP address on the P2P interface. */
    private var p2pHostAddress: InetAddress? = null

    /** The game session manager – delegated for session/state management. */
    private val sessionManager = GameSessionManager()

    /** Game session ID (generated once on start). */
    private var sessionId: String? = null

    /** Host's player name. */
    private var hostName: String = "Host"

    /** External listener for UI-layer events. */
    var networkListener: GameNetworkListener? = null

    // ── NSD Registration Listener ───────────────────────────────────────────

    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null

    // ── WiFi Direct Broadcast Receiver ──────────────────────────────────────

    private val wifiDirectReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(
                        WifiP2pManager.EXTRA_NETWORK_INFO
                    )
                    val p2pInfo = intent.getParcelableExtra<WifiP2pInfo>(
                        WifiP2pManager.EXTRA_WIFI_P2P_INFO
                    )
                    if (networkInfo?.isConnected == true && p2pInfo?.isGroupOwner == true) {
                        Log.d(TAG, "Confirmed as group owner via broadcast")
                        isGroupOwner = true
                        onGroupOwnerReady()
                    }
                }
            }
        }
    }

    private var receiverRegistered = false
    private var isGroupOwner = false

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        wifiP2pManager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        nsdManager = getSystemService(NSD_SERVICE) as NsdManager
        wifiChannel = wifiP2pManager.initialize(this, mainLooper) { /* channel lost */ }
        registerWifiReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        hostName = intent?.getStringExtra(EXTRA_PLAYER_NAME) ?: "Host"
        startHosting()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Hosting Initialisation ──────────────────────────────────────────────

    private fun startHosting() {
        Log.d(TAG, "Starting hosting as $hostName")
        // Generate session ID from host name + timestamp
        sessionId = "${hostName}_${System.currentTimeMillis()}"
        sessionManager.createSession(sessionId!!, hostName)
        createP2pGroup()
    }

    // ── WiFi Direct Group Creation ──────────────────────────────────────────

    private fun createP2pGroup() {
        val ch = wifiChannel ?: run {
            Log.e(TAG, "WiFi channel is null")
            networkListener?.onNetworkError("WiFi Direct channel not available")
            return
        }
        wifiP2pManager.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "P2P group created — this device is group owner")
                isGroupOwner = true
                onGroupOwnerReady()
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "createGroup failed: reason=$reason")
                // If group already exists, try to use it
                if (reason == WifiP2pManager.BUSY) {
                    Log.d(TAG, "Group may already exist, requesting group info")
                    requestGroupInfo()
                } else {
                    networkListener?.onNetworkError(
                        "Failed to create WiFi Direct group (code=$reason)"
                    )
                }
            }
        })
    }

    private fun requestGroupInfo() {
        val ch = wifiChannel ?: return
        wifiP2pManager.requestGroupInfo(ch) { group ->
            if (group != null) {
                Log.d(TAG, "Existing group found: ${group.networkName}")
                isGroupOwner = group.isGroupOwner
                if (isGroupOwner) onGroupOwnerReady()
            }
        }
    }

    // ── Group Owner Ready ───────────────────────────────────────────────────

    private fun onGroupOwnerReady() {
        findP2pAddress()
        registerNsdService()
        startServerSocket()
        networkListener?.onHostingStarted(sessionId ?: "unknown", hostName)
    }

    // ── P2P Address Discovery ───────────────────────────────────────────────

    private fun findP2pAddress() {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                // P2P interfaces are typically named p2p-p2p0-*, p2p0, etc.
                if (iface.name.startsWith("p2p")) {
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress && addr is InetAddress) {
                            p2pHostAddress = addr
                            Log.d(TAG, "P2P interface ${iface.name} → ${addr.hostAddress}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find P2P address", e)
        }
        // Fallback: use the well-known GO address
        if (p2pHostAddress == null) {
            try {
                p2pHostAddress = InetAddress.getByName("192.168.49.1")
                Log.d(TAG, "Using default GO address 192.168.49.1")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set default GO address", e)
            }
        }
    }

    // ── NSD Service Registration ────────────────────────────────────────────

    private fun registerNsdService() {
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "NSD service registered: ${info.serviceName}")
                networkListener?.onServiceRegistered(info.serviceName)
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD registration failed: errorCode=$errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "NSD service unregistered: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD unregistration failed: errorCode=$errorCode")
            }
        }
        nsdRegistrationListener = listener

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = serverSocket?.localPort ?: SERVER_PORT
            // Add TXT records so joiners can identify the host
            setAttribute("hostName", hostName)
            setAttribute("sessionId", sessionId ?: "")
            setAttribute("game", "GuessTheSongYear")
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NSD service", e)
            networkListener?.onNetworkError("Failed to register game service via NSD")
        }
    }

    // ── Server Socket ───────────────────────────────────────────────────────

    private fun startServerSocket() {
        acceptJob?.cancel()
        acceptJob = serviceScope.launch {
            try {
                serverSocket = ServerSocket(SERVER_PORT)
                serverSocket?.soTimeout = 0 // no timeout for accept
                val actualPort = serverSocket?.localPort ?: SERVER_PORT
                Log.d(TAG, "ServerSocket listening on port $actualPort")
                acceptClients()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start server socket", e)
                networkListener?.onNetworkError("Failed to start game server: ${e.message}")
            }
        }
    }

    private suspend fun acceptClients() {
        try {
            while (serviceScope.isActive) {
                val clientSocket = serverSocket?.accept() ?: break
                Log.d(TAG, "Client connected: ${clientSocket.inetAddress.hostAddress}")
                // Each client gets a coroutine
                serviceScope.launch {
                    handleClient(clientSocket)
                }
            }
        } catch (e: IOException) {
            if (e !is SocketException || serverSocket != null) {
                Log.e(TAG, "Accept loop error", e)
            }
        }
    }

    // ── Client Connection Handler ───────────────────────────────────────────

    private suspend fun handleClient(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT
        val connection = ClientConnection(socket)
        try {
            connection.readLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Client handler error", e)
        } finally {
            connection.disconnect()
        }
    }

    // ── Client Connection Inner Class ───────────────────────────────────────

    /**
     * Wraps a single TCP socket connection to a game client.
     * Reads JSON messages in a loop and dispatches them.
     */
    inner class ClientConnection(val socket: Socket) {
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), JSON_CHARSET))
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), JSON_CHARSET))
        private val writeMutex = Mutex()
        var playerName: String? = null
        suspend fun readLoop() {
            try {
                var line: String?
                while (true) {
                    line = try {
                        reader.readLine()
                    } catch (_: IOException) { null }
                    if (line == null) break
                    if (line.isBlank()) continue
                    try {
                        dispatchMessage(line, this)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling message from ${playerName ?: "unknown"}", e)
                    }
                }
            } catch (e: IOException) {
                Log.d(TAG, "Client ${playerName ?: "unknown"} disconnected: ${e.message}")
            }
        }

        /** Send a JSON string to this client. Thread-safe via mutex. */
        suspend fun send(json: String) {
            writeMutex.withLock {
                try {
                    writer.write(json)
                    writer.newLine()
                    writer.flush()
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to send to ${playerName ?: "unknown"}", e)
                }
            }
        }

        /** Disconnect this client and clean up. */
        fun disconnect() {
            val name = playerName
            if (name != null) {
                clients.remove(name)
                // Notify session manager
                sessionManager.leaveSession(sessionId ?: return, name)
                networkListener?.onPlayerDisconnected(name)
                // Broadcast player left to remaining clients
                serviceScope.launch {
                    broadcastPlayerLeft(name)
                }
            }
            try {
                socket.close()
            } catch (_: IOException) {}
        }
    }

    // ── Message Dispatch ────────────────────────────────────────────────────

    private suspend fun dispatchMessage(json: String, connection: ClientConnection) {
        val msg = try {
            JSONObject(json)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid JSON from client: $json")
            return
        }

        val type = msg.optString("type")

        @Suppress("UNUSED")
        val session = sessionManager.getSession(sessionId ?: "") ?: return

        when (type) {
            MSG_JOIN -> handleJoin(msg, connection)
            MSG_GUESS -> handleGuess(msg, connection)
            MSG_END -> handleEnd(msg, connection)
            else -> Log.w(TAG, "Unknown message type from client: $type")
        }
    }

    // ── JOIN Handling ───────────────────────────────────────────────────────

    private suspend fun handleJoin(msg: JSONObject, connection: ClientConnection) {
        val playerName = msg.optString("player", "Unknown")
        val sid = sessionId ?: return

        // Register with session manager
        sessionManager.joinSession(sid, playerName)
        connection.playerName = playerName
        clients[playerName] = connection

        Log.d(TAG, "Player joined: $playerName")

        // Send JOIN_ACK to the new player (initial state)
        val ackPayload = buildJson {
            put("type", MSG_JOIN_ACK)
            put("sessionId", sid)
            put("hostName", hostName)
            put("players", buildJsonArray {
                sessionManager.getPlayers(sid).forEach { player ->
                    put(buildJson {
                        put("name", player.name)
                        put("score", player.score)
                        put("isHost", player.isHost)
                    })
                }
            })
            // Include current game state if any
            val gameSession = sessionManager.getSession(sid)
            gameSession?.let { gs ->
                if (gs.currentVideoId != null) {
                    put("currentVideoId", gs.currentVideoId)
                    put("currentYear", gs.currentYear)
                    put("currentTitle", gs.currentTitle)
                }
                put("currentPlayerIndex", gs.currentPlayerIndex)
                put("currentPlayer", sessionManager.getCurrentPlayer(sid) ?: "")
            }
        }

        connection.send(ackPayload.toString())

        // Notify listener
        networkListener?.onPlayerJoined(playerName, socketAddress(connection))

        // Broadcast updated player list to all clients
        broadcastPlayerList()
    }

    // ── GUESS Handling ──────────────────────────────────────────────────────

    private suspend fun handleGuess(msg: JSONObject, connection: ClientConnection) {
        val playerName = connection.playerName ?: msg.optString("player", "Unknown")
        val guess = msg.optInt("guess", 0)
        val sid = sessionId ?: return
        val gameSession = sessionManager.getSession(sid) ?: return
        val correctYear = gameSession.currentYear ?: 0

        // Evaluate score
        val result = ScoreManager.evaluateGuess(
            guessedYear = guess,
            actualYear = correctYear,
            difficulty = Difficulty.MEDIUM // use current game difficulty
        )

        val newScore = result.pointsEarned
        sessionManager.updateScore(sid, playerName, newScore)

        Log.d(TAG, "Guess from $playerName: $guess (correct=$correctYear), earned=$newScore")

        // Broadcast GUESS result to ALL connected clients
        val guessPayload = buildJson {
            put("type", MSG_GUESS)
            put("player", playerName)
            put("guess", guess)
            put("correctYear", correctYear)
            put("score", newScore)
            put("pointsEarned", result.pointsEarned)
            put("difference", result.difference)
            put("isCorrect", result.isCorrect)
        }

        broadcastToAll(guessPayload.toString())

        // Update player list with new scores
        broadcastPlayerList()
    }

    // ── END Handling (client-initiated disconnect) ──────────────────────────

    private suspend fun handleEnd(msg: JSONObject, connection: ClientConnection) {
        val playerName = connection.playerName ?: msg.optString("player")
        Log.d(TAG, "End received from $playerName")
        connection.disconnect()
    }

    // ── Broadcast Methods ───────────────────────────────────────────────────

    /** Send a JSON payload to every connected client. */
    private suspend fun broadcastToAll(json: String) {
        broadcastMutex.withLock {
            clients.values.toList().forEach { client ->
                client.send(json)
            }
        }
    }

    /** Send a JSON payload to every client except the sender. */
    private suspend fun broadcastExcept(json: String, exceptPlayer: String) {
        broadcastMutex.withLock {
            clients.entries.forEach { (name, client) ->
                if (name != exceptPlayer) {
                    client.send(json)
                }
            }
        }
    }

    /** Broadcast a VIDEO message to all clients. */
    suspend fun broadcastVideo(videoId: String, year: Int, title: String) {
        val sid = sessionId ?: return
        sessionManager.setVideo(sid, videoId, year, title)

        val payload = buildJson {
            put("type", MSG_VIDEO)
            put("id", videoId)
            put("year", year)
            put("title", title)
        }
        broadcastToAll(payload.toString())
    }

    /** Broadcast a TURN change to all clients. */
    suspend fun broadcastTurn(playerName: String) {
        val sid = sessionId ?: return
        sessionManager.nextTurn(sid)

        val payload = buildJson {
            put("type", MSG_TURN)
            put("player", playerName)
        }
        broadcastToAll(payload.toString())
        networkListener?.onTurnReceived(playerName)
    }

    /** Broadcast updated player list to all clients. */
    private suspend fun broadcastPlayerList() {
        val sid = sessionId ?: return
        val payload = buildJson {
            put("type", MSG_PLAYER_LIST)
            put("players", buildJsonArray {
                sessionManager.getPlayers(sid).forEach { player ->
                    put(buildJson {
                        put("name", player.name)
                        put("score", player.score)
                        put("isHost", player.isHost)
                    })
                }
            })
            put("currentPlayer", sessionManager.getCurrentPlayer(sid) ?: "")
        }
        broadcastToAll(payload.toString())
    }

    /** Broadcast that a player left. */
    private suspend fun broadcastPlayerLeft(playerName: String) {
        val payload = buildJson {
            put("type", MSG_PLAYER_LEFT)
            put("player", playerName)
        }
        broadcastToAll(payload.toString())
    }

    /** Broadcast game END to all clients. */
    suspend fun broadcastEnd() {
        val payload = buildJson {
            put("type", MSG_END)
        }
        broadcastToAll(payload.toString())
        networkListener?.onSessionEnded()
        // Stop the service after a brief delay
        serviceScope.launch {
            delay(2000)
            stopSelf()
        }
    }

    /** Send a GUESS result broadcast (used from the host's own game UI). */
    suspend fun sendGuessResult(
        playerName: String,
        guess: Int,
        correctYear: Int,
        score: Int,
        pointsEarned: Int,
        difference: Int,
        isCorrect: Boolean
    ) {
        val sid = sessionId ?: return
        sessionManager.updateScore(sid, playerName, score)

        val payload = buildJson {
            put("type", MSG_GUESS)
            put("player", playerName)
            put("guess", guess)
            put("correctYear", correctYear)
            put("score", score)
            put("pointsEarned", pointsEarned)
            put("difference", difference)
            put("isCorrect", isCorrect)
        }
        broadcastToAll(payload.toString())
        broadcastPlayerList()
    }

    // ── Utilities ───────────────────────────────────────────────────────────

    private fun socketAddress(connection: ClientConnection): String {
        return try {
            connection.socket.inetAddress.hostAddress ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun registerWifiReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        registerReceiver(wifiDirectReceiver, filter, RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    private fun shutdown() {
        Log.d(TAG, "Shutting down HostGameService")
        acceptJob?.cancel()
        acceptJob = null

        // Disconnect all clients
        clients.values.toList().forEach { it.disconnect() }
        clients.clear()

        // Close server socket
        try {
            serverSocket?.close()
        } catch (_: IOException) {}
        serverSocket = null

        // Remove P2P group
        try {
            wifiChannel?.let { wifiP2pManager.removeGroup(it, null) }
        } catch (_: Exception) {}

        // Unregister NSD
        try {
            nsdRegistrationListener?.let { nsdManager.unregisterService(it) }
        } catch (_: Exception) {}
        nsdRegistrationListener = null

        // Remove session
        sessionId?.let { sessionManager.removeSession(it) }

        // Unregister receiver
        if (receiverRegistered) {
            try { unregisterReceiver(wifiDirectReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }

        serviceScope.cancel()
        networkListener = null
    }

    // ── Inline JSON Builders (minSdk 24 compatible) ─────────────────────────

    private inline fun buildJson(block: JSONObject.() -> Unit): JSONObject =
        JSONObject().apply(block)

    private inline fun buildJsonArray(block: JSONArray.() -> Unit): JSONArray =
        JSONArray().apply(block)
}
