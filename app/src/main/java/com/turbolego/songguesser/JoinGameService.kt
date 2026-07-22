package com.turbolego.songguesser

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Android Service that discovers and joins a WiFi Direct + NSD hosted game session.
 *
 * Responsibilities:
 * - Discovers nearby game hosts via NSD (Network Service Discovery)
 * - Resolves the discovered service to obtain host details
 * - Initiates a WiFi Direct P2P connection to the host
 * - Opens a TCP socket to the host and exchanges game protocol messages
 * - Dispatches game events via [GameNetworkListener]
 * - Handles reconnection and graceful disconnect
 */
class JoinGameService : Service() {

    // ── Constants ───────────────────────────────────────────────────────────

    private val TAG = "JoinGameService"
    private val SERVICE_TYPE = "_guessgame._tcp"
    private val CONNECT_TIMEOUT = 15_000
    private val SOCKET_TIMEOUT = 30_000
    private val RECONNECT_DELAY = 3_000L
    private val MAX_RECONNECT_ATTEMPTS = 3
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

        private const val EXTRA_PLAYER_NAME = "player_name"
        private const val EXTRA_HOST_DEVICE_ADDRESS = "host_device_address"

        /**
         * Start discovering + joining a game session.
         * Call [startDiscovery] first, or [joinHost] if you already know the host.
         */
        fun startDiscovery(context: Context, playerName: String) {
            val intent = Intent(context, JoinGameService::class.java).apply {
                putExtra(EXTRA_PLAYER_NAME, playerName)
                action = ACTION_DISCOVER
            }
            context.startService(intent)
        }

        /**
         * Start joining a specific host by WiFi Direct device address.
         */
        fun joinHost(context: Context, playerName: String, hostDeviceAddress: String) {
            val intent = Intent(context, JoinGameService::class.java).apply {
                putExtra(EXTRA_PLAYER_NAME, playerName)
                putExtra(EXTRA_HOST_DEVICE_ADDRESS, hostDeviceAddress)
                action = ACTION_JOIN_HOST
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JoinGameService::class.java))
        }

        private const val ACTION_DISCOVER = "com.turbolego.songguesser.action.DISCOVER"
        private const val ACTION_JOIN_HOST = "com.turbolego.songguesser.action.JOIN_HOST"
    }

    // ── Services & State ────────────────────────────────────────────────────

    private lateinit var wifiP2pManager: WifiP2pManager
    private var wifiChannel: WifiP2pManager.Channel? = null
    private lateinit var nsdManager: NsdManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socketJob: Job? = null
    private var gameSocket: Socket? = null
    private var writer: BufferedWriter? = null
    private val writeMutex = Mutex()
    private var reconnectAttempts = 0

    /** Player name of the local user. */
    private var playerName: String = ""

    /** Host's WiFi Direct device address (from NSD resolution or direct input). */
    private var hostDeviceAddress: String? = null

    /** Host's IP address within the P2P group. */
    private var hostAddress: java.net.InetAddress? = null

    /** The port the host's game server is listening on. */
    private var hostPort: Int = 8888

    /** Current host name (display name from the host). */
    private var hostName: String? = null

    /** Session ID assigned by the host. */
    private var sessionId: String? = null

    /** Whether we have successfully joined a session. */
    private var joined = false

    /** External listener for UI-layer events. */
    var networkListener: GameNetworkListener? = null

    /** Queue of discovered services (avoid duplicates). */
    private val discoveredServices = ConcurrentLinkedQueue<NsdServiceInfo>()

    // ── NSD Listeners ───────────────────────────────────────────────────────

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListener: NsdManager.ResolveListener? = null

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
                    if (networkInfo?.isConnected == true && p2pInfo != null) {
                        onP2pConnected(p2pInfo)
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    // Device info changed — could retry if needed
                }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        Log.d(TAG, "WiFi Direct enabled")
                    } else {
                        Log.w(TAG, "WiFi Direct disabled")
                        networkListener?.onNetworkError("WiFi Direct is disabled")
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    // Peers changed — could trigger discovery
                }
            }
        }
    }

    private var receiverRegistered = false

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
        playerName = intent?.getStringExtra(EXTRA_PLAYER_NAME) ?: "Player"

        when (intent?.action) {
            ACTION_DISCOVER -> startNsdDiscovery()
            ACTION_JOIN_HOST -> {
                hostDeviceAddress = intent.getStringExtra(EXTRA_HOST_DEVICE_ADDRESS)
                hostDeviceAddress?.let { address ->
                    connectToHostP2p(address)
                } ?: run {
                    Log.e(TAG, "No host device address provided for ACTION_JOIN_HOST")
                    networkListener?.onNetworkError("No host device address provided")
                }
            }
            else -> {
                Log.d(TAG, "No action specified, starting NSD discovery by default")
                startNsdDiscovery()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── NSD Discovery ───────────────────────────────────────────────────────

    /**
     * Start discovering game hosts via NSD.
     */
    private fun startNsdDiscovery() {
        Log.d(TAG, "Starting NSD discovery for $SERVICE_TYPE")
        discoveredServices.clear()

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "NSD discovery started: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "NSD service found: ${service.serviceName} (${service.serviceType})")
                // Filter for our service type
                if (service.serviceType == SERVICE_TYPE) {
                    if (!discoveredServices.contains(service)) {
                        discoveredServices.add(service)
                        // Automatically resolve the first discovered service
                        resolveService(service)
                    }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "NSD service lost: ${service.serviceName}")
                discoveredServices.remove(service)
            }

            override fun onDiscoveryStopped(regType: String) {
                Log.d(TAG, "NSD discovery stopped: $regType")
            }

            override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                Log.e(TAG, "NSD start discovery failed: errorCode=$errorCode")
                networkListener?.onNetworkError("Failed to discover game sessions: error $errorCode")
            }

            override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {
                Log.e(TAG, "NSD stop discovery failed: errorCode=$errorCode")
            }
        }
        this.discoveryListener = discoveryListener

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NSD discovery", e)
            networkListener?.onNetworkError("Failed to discover game sessions: ${e.message}")
        }
    }

    // ── NSD Service Resolution ──────────────────────────────────────────────

    private fun resolveService(service: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD resolve failed: ${service.serviceName}, errorCode=$errorCode")
            }

            override fun onServiceResolved(service: NsdServiceInfo) {
                Log.d(TAG, "NSD service resolved: ${service.serviceName}")
                Log.d(TAG, "  Host: ${service.host?.hostAddress}")
                Log.d(TAG, "  Port: ${service.port}")

                // Extract metadata from TXT records
                hostName = service.serviceName
                hostAddress = service.host
                hostPort = service.port

                // Extract host device address from attributes if available
                val txtMap = HashMap<String, String>()
                try {
                    val attributes = service.attributes
                    if (attributes != null) {
                        for ((key, value) in attributes) {
                            txtMap[key] = String(value, JSON_CHARSET)
                        }
                    }
                } catch (_: Exception) {}
                hostDeviceAddress = txtMap["deviceAddress"]

                networkListener?.onServiceRegistered(service.serviceName)

                // Initiate WiFi Direct connection to the host
                if (hostDeviceAddress != null) {
                    connectToHostP2p(hostDeviceAddress!!)
                } else {
                    // If we don't have the device address, try direct socket
                    // This works if already on the same P2P network
                    Log.d(TAG, "No device address in TXT records, trying direct socket")
                    serviceScope.launch {
                        connectSocket()
                    }
                }
            }
        }
        this.resolveListener = resolveListener

        try {
            nsdManager.resolveService(service, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve NSD service", e)
        }
    }

    // ── WiFi Direct Connection ──────────────────────────────────────────────

    /**
     * Initiate a WiFi Direct P2P connection to the host device.
     * When the connection is established, [onP2pConnected] is called via the broadcast receiver.
     */
    private fun connectToHostP2p(hostDeviceAddress: String) {
        Log.d(TAG, "Initiating P2P connection to device: $hostDeviceAddress")
        val ch = wifiChannel ?: run {
            Log.e(TAG, "WiFi channel is null, cannot connect")
            networkListener?.onNetworkError("WiFi Direct channel not available")
            return
        }

        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            groupOwnerIntent = 0 // We want the other device to be GO
        }

        wifiP2pManager.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "P2P connection request sent successfully")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "P2P connection failed: reason=$reason")
                networkListener?.onNetworkError(
                    "Failed to connect to host via WiFi Direct (code=$reason)"
                )
            }
        })
    }

    /**
     * Called when the broadcast receiver detects that the P2P connection is established.
     */
    private fun onP2pConnected(p2pInfo: WifiP2pInfo) {
        Log.d(TAG, "P2P connected. Group owner: ${p2pInfo.groupOwnerAddress?.hostAddress}")
        Log.d(TAG, "isGroupOwner: ${p2pInfo.isGroupOwner}")

        if (p2pInfo.groupFormed && p2pInfo.groupOwnerAddress != null) {
            hostAddress = p2pInfo.groupOwnerAddress
            Log.d(TAG, "Host address from P2P info: ${hostAddress?.hostAddress}")
            // Open the game socket
            serviceScope.launch {
                connectSocket()
            }
        }
    }

    // ── Socket Connection ───────────────────────────────────────────────────

    /**
     * Open a TCP socket to the game host and start the game protocol exchange.
     */
    private suspend fun connectSocket() {
        val host = hostAddress ?: run {
            Log.e(TAG, "No host address available")
            networkListener?.onNetworkError("No host address to connect to")
            return
        }

        socketJob?.cancel()
        socketJob = serviceScope.launch {
            try {
                Log.d(TAG, "Connecting socket to ${host.hostAddress}:$hostPort")
                val socket = Socket()
                socket.connect(InetSocketAddress(host, hostPort), CONNECT_TIMEOUT)
                socket.soTimeout = SOCKET_TIMEOUT
                gameSocket = socket

                writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), JSON_CHARSET))
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), JSON_CHARSET))

                // Reset reconnect counter on successful connection
                reconnectAttempts = 0

                // Send JOIN message
                sendJoin()

                // Enter read loop for game messages
                readLoop(reader)
            } catch (e: IOException) {
                Log.e(TAG, "Socket connection failed", e)
                networkListener?.onNetworkError("Connection to host failed: ${e.message}")
                attemptReconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Socket error", e)
                networkListener?.onNetworkError("Connection error: ${e.message}")
            }
        }
    }

    // ── Read Loop ───────────────────────────────────────────────────────────

    private suspend fun readLoop(reader: BufferedReader) {
        try {
            var line: String?
            while (true) {
                private suspend fun readLoop(reader: BufferedReader) {
                    try {
                        var line: String?
                        while (true) {
                            line = try {
                                reader.readLine()
                            } catch (_: IOException) { null }
                            if (line == null) break
                            if (line.isBlank()) continue
                            try {
                                dispatchMessage(line)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error dispatching message", e)
                            }
                        }
        } catch (e: IOException) {
            Log.d(TAG, "Read loop ended: ${e.message}")
        } finally {
            Log.d(TAG, "Read loop finished")
            if (joined) {
                networkListener?.onSessionEnded()
            }
            disconnectSocket()
        }
    }

    // ── Message Dispatch ────────────────────────────────────────────────────

    private fun dispatchMessage(json: String) {
        val msg = try {
            JSONObject(json)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid JSON from host: $json")
            return
        }

        val type = msg.optString("type")
        Log.d(TAG, "Received message type=$type")

        when (type) {
            MSG_JOIN_ACK -> handleJoinAck(msg)
            MSG_PLAYER_LIST -> handlePlayerList(msg)
            MSG_VIDEO -> handleVideo(msg)
            MSG_TURN -> handleTurn(msg)
            MSG_GUESS -> handleGuess(msg)
            MSG_END -> handleEnd(msg)
            MSG_PLAYER_LEFT -> handlePlayerLeft(msg)
            else -> Log.w(TAG, "Unknown message type: $type")
        }
    }

    // ── Message Handlers ────────────────────────────────────────────────────

    private fun handleJoinAck(msg: JSONObject) {
        sessionId = msg.optString("sessionId")
        hostName = msg.optString("hostName", "Host")
        joined = true

        Log.d(TAG, "Joined session: $sessionId (host: $hostName)")

        // Build the GameSession from the join ack data
        val gameSession = GameSessionManager.GameSession(
            sessionId = sessionId ?: "",
            hostName = hostName ?: "",
            players = mutableMapOf(),
            currentVideoId = msg.optString("currentVideoId", null),
            currentYear = if (msg.has("currentYear")) msg.optInt("currentYear") else null,
            currentTitle = msg.optString("currentTitle", null),
            currentPlayerIndex = msg.optInt("currentPlayerIndex", 0),
            isHost = false
        )

        // Populate players from the players JSON array
        val playersArray = msg.optJSONArray("players")
        if (playersArray != null) {
            for (i in 0 until playersArray.length()) {
                val playerObj = playersArray.getJSONObject(i)
                val pName = playerObj.optString("name", "Unknown")
                val pScore = playerObj.optInt("score", 0)
                val pIsHost = playerObj.optBoolean("isHost", false)
                gameSession.players[pName] = GameSessionManager.GameSession.PlayerInfo(
                    name = pName,
                    score = pScore,
                    isHost = pIsHost
                )
            }
        }

        networkListener?.onJoinedSession(gameSession)
    }

    private fun handlePlayerList(msg: JSONObject) {
        val playersArray = msg.optJSONArray("players")
        val currentPlayer = msg.optString("currentPlayer", "")
        Log.d(TAG, "Player list updated, current player: $currentPlayer")
        if (playersArray != null) {
            for (i in 0 until playersArray.length()) {
                val playerObj = playersArray.getJSONObject(i)
                Log.d(TAG, "  ${playerObj.optString("name")}: ${playerObj.optInt("score")}")
            }
        }
    }

    private fun handleVideo(msg: JSONObject) {
        val videoId = msg.optString("id", "")
        val year = msg.optInt("year", 0)
        val title = msg.optString("title", "")

        Log.d(TAG, "Video update: $title ($year) id=$videoId")
        networkListener?.onVideoReceived(videoId, year, title)
    }

    private fun handleTurn(msg: JSONObject) {
        val player = msg.optString("player", "")
        Log.d(TAG, "Turn changed to: $player")
        networkListener?.onTurnReceived(player)
    }

    private fun handleGuess(msg: JSONObject) {
        val player = msg.optString("player", "")
        val guess = msg.optInt("guess", 0)
        val correctYear = msg.optInt("correctYear", 0)
        val score = msg.optInt("score", 0)

        Log.d(TAG, "Guess from $player: $guess (correct=$correctYear, score=$score)")
        networkListener?.onGuessReceived(player, guess, correctYear, score)
    }

    private fun handleEnd(msg: JSONObject) {
        Log.d(TAG, "Game ended by host")
        joined = false
        networkListener?.onSessionEnded()
        serviceScope.launch {
            delay(1000)
            stopSelf()
        }
    }

    private fun handlePlayerLeft(msg: JSONObject) {
        val player = msg.optString("player", "")
        Log.d(TAG, "Player left: $player")
        networkListener?.onPlayerDisconnected(player)
    }

    // ── Outgoing Messages ───────────────────────────────────────────────────

    /** Send the JOIN message to the host. */
    private suspend fun sendJoin() {
        val payload = JSONObject().apply {
            put("type", MSG_JOIN)
            put("player", playerName)
        }
        sendMessage(payload.toString())
    }

    /**
     * Send a GUESS message to the host.
     * Called when the local player submits a year guess.
     */
    suspend fun sendGuess(guess: Int) {
        if (!joined) {
            Log.w(TAG, "Cannot send guess: not joined to a session")
            return
        }
        val payload = JSONObject().apply {
            put("type", MSG_GUESS)
            put("player", playerName)
            put("guess", guess)
        }
        sendMessage(payload.toString())
    }

    /**
     * Send an END message to the host indicating the local player is leaving.
     */
    suspend fun sendLeave() {
        if (!joined) return
        val payload = JSONObject().apply {
            put("type", MSG_END)
            put("player", playerName)
        }
        sendMessage(payload.toString())
        joined = false
    }

    // ── Socket Write ────────────────────────────────────────────────────────

    private suspend fun sendMessage(json: String) {
        writeMutex.withLock {
            try {
                writer?.let { w ->
                    w.write(json)
                    w.newLine()
                    w.flush()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send message", e)
                networkListener?.onNetworkError("Failed to send message: ${e.message}")
            }
        }
    }

    // ── Reconnection ────────────────────────────────────────────────────────

    private fun attemptReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS || joined) return
        reconnectAttempts++
        Log.d(TAG, "Reconnection attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS")
        serviceScope.launch {
            delay(RECONNECT_DELAY)
            connectSocket()
        }
    }

    // ── Utilities ───────────────────────────────────────────────────────────

    private fun disconnectSocket() {
        try {
            writer?.close()
        } catch (_: IOException) {}
        writer = null
        try {
            gameSocket?.close()
        } catch (_: IOException) {}
        gameSocket = null
    }

    private fun registerWifiReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        // Register with RECEIVER_EXPORTED flag for API 34+
        @Suppress("DEPRECATION")
        registerReceiver(wifiDirectReceiver, filter, RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    private fun shutdown() {
        Log.d(TAG, "Shutting down JoinGameService")
        socketJob?.cancel()
        socketJob = null
        disconnectSocket()

        // Stop NSD discovery
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (_: Exception) {}
        discoveryListener = null
        resolveListener = null

        // Disconnect P2P
        try {
            wifiChannel?.let { wifiP2pManager.removeGroup(it, null) }
        } catch (_: Exception) {}

        // Unregister receiver
        if (receiverRegistered) {
            try { unregisterReceiver(wifiDirectReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }

        serviceScope.cancel()
        discoveredServices.clear()
        joined = false
        networkListener = null
    }
}
