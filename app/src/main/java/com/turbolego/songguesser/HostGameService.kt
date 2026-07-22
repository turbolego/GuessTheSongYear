package com.turbolego.songguesser

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
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
import java.io.*
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Android Service that hosts a multiplayer game session.
 *
 * Supports two transports:
 * - "wifi"  : WiFi Direct P2P group + NSD service registration + TCP socket server
 * - "bluetooth" : Bluetooth RFCOMM server socket + SDP service record
 *
 * Protocol messages handled:
 *   JOIN, JOIN_ACK, PLAYER_LIST, VIDEO, REVEAL, GUESS_BLIND, REVEAL_RESULT,
 *   PLAYER_LEFT, END
 *
 * Key new protocol flow (replacing old TURN + GUESS broadcast):
 *   1) Host sends VIDEO to all clients when a new video loads.
 *   2) Host sends REVEAL to all clients when host presses "Vis svar".
 *   3) Clients send GUESS_BLIND back (stored privately — not broadcasted).
 *   4) Host computes scores and broadcasts REVEAL_RESULT to all clients.
 */
class HostGameService : Service() {

    // ── Constants ───────────────────────────────────────────────────────────

    private val TAG = "HostGameService"
    private val JSON_CHARSET = Charsets.UTF_8
    private val BT_UUID = UUID.fromString(Protocol.BT_SERVICE_UUID)

    // ── Companion & static API ──────────────────────────────────────────────

    companion object {
        @Volatile
        var instance: HostGameService? = null

        private const val EXTRA_PLAYER_NAME = "player_name"
        private const val EXTRA_TRANSPORT = "transport"

        /**
         * Start hosting a game session.
         * @param transport "wifi" or "bluetooth" — determines the transport layer.
         */
        fun start(context: Context, playerName: String, transport: String = Protocol.TRANSPORT_WIFI) {
            val intent = Intent(context, HostGameService::class.java).apply {
                putExtra(EXTRA_PLAYER_NAME, playerName)
                putExtra(EXTRA_TRANSPORT, transport)
            }
            context.startForegroundService(intent)
        }

        /**
         * Convenience method to stop the hosting service.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, HostGameService::class.java))
        }
    }

    // ── State ───────────────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** WiFi Direct manager + channel (null if transport != "wifi"). */
    private var wifiP2pManager: WifiP2pManager? = null
    private var wifiChannel: WifiP2pManager.Channel? = null

    /** NSD manager (null if transport != "wifi"). */
    private var nsdManager: NsdManager? = null

    /** Bluetooth adapter (null if transport != "bluetooth"). */
    private var bluetoothAdapter: BluetoothAdapter? = null

    /** Server socket — either TCP (WiFi) or Bluetooth RFCOMM (BT). */
    private var serverSocket: Any? = null // ServerSocket or BluetoothServerSocket

    /** Coroutine job for the accept loop. */
    private var acceptJob: Job? = null

    /** Active client connections keyed by player name. */
    private val clients = ConcurrentHashMap<String, ClientConnection>()

    /** Guards broadcast operations. */
    private val broadcastMutex = Mutex()

    /** The game session manager. */
    private val sessionManager = GameSessionManager()

    /** Game session ID. */
    private var sessionId: String? = null

    /** Host's player name. */
    private var hostName: String = "Host"

    /** Transport type. */
    private var transport: String = Protocol.TRANSPORT_WIFI

    /** P2P host address (WiFi only). */
    private var p2pHostAddress: InetAddress? = null

    /** NSD registration listener. */
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null

    /** Whether the broadcast receiver has been registered. */
    private var receiverRegistered = false

    /** External listener for UI-layer events. */
    var networkListener: GameNetworkListener? = null

    /** Whether we are confirmed as group owner (WiFi only). */
    private var isGroupOwner = false

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

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "onCreate")
        // Get system services sparingly — transport is known in onStartCommand
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        hostName = intent?.getStringExtra(EXTRA_PLAYER_NAME) ?: "Host"
        transport = intent?.getStringExtra(EXTRA_TRANSPORT) ?: Protocol.TRANSPORT_WIFI
        startHosting()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        instance = null
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Hosting Initialisation ──────────────────────────────────────────────

    private fun startHosting() {
        Log.d(TAG, "Starting hosting as $hostName (transport=$transport)")
        sessionId = "${hostName}_${System.currentTimeMillis()}"
        sessionManager.createSession(sessionId!!, hostName)

        when (transport) {
            Protocol.TRANSPORT_BLUETOOTH -> startBluetoothHosting()
            else -> startWifiHosting()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Wi-Fi TRANSPORT
    // ══════════════════════════════════════════════════════════════════════════

    private fun startWifiHosting() {
        Log.d(TAG, "Starting Wi-Fi Direct hosting")
        wifiP2pManager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        nsdManager = getSystemService(NSD_SERVICE) as NsdManager
        wifiChannel = wifiP2pManager?.initialize(this, mainLooper) { /* channel lost */ }
        registerWifiReceiver()
        createP2pGroup()
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

    private fun createP2pGroup() {
        val ch = wifiChannel ?: run {
            Log.e(TAG, "WiFi channel is null")
            networkListener?.onNetworkError("WiFi Direct channel not available")
            return
        }
        wifiP2pManager?.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "P2P group created — this device is group owner")
                isGroupOwner = true
                onGroupOwnerReady()
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "createGroup failed: reason=$reason")
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
        wifiP2pManager?.requestGroupInfo(ch) { group ->
            if (group != null) {
                Log.d(TAG, "Existing group found: ${group.networkName}")
                isGroupOwner = group.isGroupOwner
                if (isGroupOwner) onGroupOwnerReady()
            }
        }
    }

    private fun onGroupOwnerReady() {
        findP2pAddress()
        registerNsdService()
        startTcpServerSocket()
        networkListener?.onHostingStarted(sessionId ?: "unknown", hostName)
    }

    private fun findP2pAddress() {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
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
        if (p2pHostAddress == null) {
            try {
                p2pHostAddress = InetAddress.getByName("192.168.49.1")
                Log.d(TAG, "Using default GO address 192.168.49.1")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set default GO address", e)
            }
        }
    }

    private fun registerNsdService() {
        val mgr = nsdManager ?: return
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

        val tcpSocket = serverSocket as? ServerSocket
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = Protocol.WIFI_SERVICE_NAME
            serviceType = Protocol.WIFI_SERVICE_TYPE
            port = tcpSocket?.localPort ?: Protocol.WIFI_SERVER_PORT
            setAttribute("hostName", hostName)
            setAttribute("sessionId", sessionId ?: "")
            setAttribute("game", "GuessTheSongYear")
        }

        try {
            mgr.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NSD service", e)
            networkListener?.onNetworkError("Failed to register game service via NSD")
        }
    }

    private fun startTcpServerSocket() {
        acceptJob?.cancel()
        acceptJob = serviceScope.launch {
            try {
                val socket = ServerSocket(Protocol.WIFI_SERVER_PORT)
                socket.soTimeout = 0
                serverSocket = socket
                Log.d(TAG, "TCP ServerSocket listening on port ${socket.localPort}")
                acceptTcpClients(socket)
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start TCP server socket", e)
                networkListener?.onNetworkError("Failed to start game server: ${e.message}")
            }
        }
    }

    private suspend fun acceptTcpClients(serverSocket: ServerSocket) {
        try {
            while (serviceScope.isActive) {
                val clientSocket = serverSocket.accept() ?: break
                Log.d(TAG, "TCP client connected: ${clientSocket.inetAddress.hostAddress}")
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

    // ══════════════════════════════════════════════════════════════════════════
    // BLUETOOTH TRANSPORT
    // ══════════════════════════════════════════════════════════════════════════

    private fun startBluetoothHosting() {
        Log.d(TAG, "Starting Bluetooth RFCOMM hosting")
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            networkListener?.onNetworkError("Bluetooth is not supported on this device")
            return
        }
        if (!bluetoothAdapter!!.isEnabled) {
            networkListener?.onNetworkError("Bluetooth is not enabled")
            return
        }
        startBluetoothServerSocket()
        networkListener?.onHostingStarted(sessionId ?: "unknown", hostName)
    }

    private fun startBluetoothServerSocket() {
        acceptJob?.cancel()
        acceptJob = serviceScope.launch {
            try {
                val btAdapter = bluetoothAdapter ?: return@launch
                // Use listenUsingRfcommWithServiceRecord for SDP registration
                val btServerSocket: BluetoothServerSocket =
                    btAdapter.listenUsingRfcommWithServiceRecord(
                        Protocol.BT_SERVICE_NAME,
                        BT_UUID
                    )
                serverSocket = btServerSocket
                Log.d(TAG, "Bluetooth server socket listening")
                acceptBluetoothClients(btServerSocket)
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start Bluetooth server socket", e)
                networkListener?.onNetworkError("Failed to start Bluetooth server: ${e.message}")
            }
        }
    }

    @Suppress("MissingPermission")
    private suspend fun acceptBluetoothClients(btServerSocket: BluetoothServerSocket) {
        try {
            while (serviceScope.isActive) {
                val btSocket: BluetoothSocket? = try {
                    btServerSocket.accept()
                } catch (e: IOException) {
                    Log.e(TAG, "Bluetooth accept error", e)
                    null
                }
                if (btSocket == null) break
                val deviceName = btSocket.remoteDevice?.name ?: "unknown"
                Log.d(TAG, "Bluetooth client connected: $deviceName")
                serviceScope.launch {
                    // Wrap BluetoothSocket in stream-based ClientConnection
                    handleBluetoothClient(btSocket)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Bluetooth accept loop ended", e)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CLIENT CONNECTION (shared for TCP and Bluetooth)
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun handleClient(socket: Socket) {
        socket.soTimeout = Protocol.SOCKET_TIMEOUT_MS
        val connection = ClientConnection(
            socket.getInputStream(),
            socket.getOutputStream()
        ).apply { remoteAddress = socket.inetAddress?.hostAddress ?: "unknown" }
        try {
            connection.readLoop()
        } catch (e: Exception) {
            Log.e(TAG, "TCP client handler error", e)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun handleBluetoothClient(btSocket: BluetoothSocket) {
        try {
            btSocket.inputStream
            btSocket.outputStream
        } catch (e: IOException) {
            Log.e(TAG, "Failed to get BT streams", e)
            try { btSocket.close() } catch (_: Exception) {}
            return
        }
        val connection = ClientConnection(
            btSocket.inputStream,
            btSocket.outputStream
        ).apply { remoteAddress = btSocket.remoteDevice?.address ?: "bt:unknown" }
        try {
            connection.readLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth client handler error", e)
        } finally {
            connection.disconnect()
        }
    }

    // ── Client Connection Inner Class ───────────────────────────────────────

    /**
     * Wraps a single connection to a game client — works over TCP or Bluetooth RFCOMM.
     */
    inner class ClientConnection constructor(
        private val inputStream: InputStream,
        private val outputStream: OutputStream
    ) {
        private val reader = BufferedReader(InputStreamReader(inputStream, JSON_CHARSET))
        private val writer = BufferedWriter(OutputStreamWriter(outputStream, JSON_CHARSET))
        private val writeMutex = Mutex()
        var playerName: String? = null
        var remoteAddress: String = "unknown"

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
                sessionManager.leaveSession(sessionId ?: return, name)
                networkListener?.onPlayerDisconnected(name)
                serviceScope.launch {
                    broadcastPlayerLeft(name)
                }
            }
            try {
                reader.close()
            } catch (_: IOException) {}
            try {
                writer.close()
            } catch (_: IOException) {}
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MESSAGE DISPATCH
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun dispatchMessage(json: String, connection: ClientConnection) {
        val msg = Protocol.tryParse(json) ?: run {
            Log.w(TAG, "Invalid JSON from client: $json")
            return
        }

        val type = msg.optString(Protocol.FIELD_TYPE)
        val sid = sessionId ?: return

        when (type) {
            Protocol.MSG_JOIN -> handleJoin(msg, connection)
            Protocol.MSG_GUESS_BLIND -> handleGuessBlind(msg, connection)
            Protocol.MSG_END -> handleEnd(msg, connection)
            else -> Log.w(TAG, "Unknown message type from client: $type")
        }
    }

    // ── JOIN Handling ───────────────────────────────────────────────────────

    private suspend fun handleJoin(msg: org.json.JSONObject, connection: ClientConnection) {
        val playerName = msg.optString(Protocol.FIELD_PLAYER, "Unknown")
        val sid = sessionId ?: return

        sessionManager.joinSession(sid, playerName)
        connection.playerName = playerName
        clients[playerName] = connection

        Log.d(TAG, "Player joined: $playerName")

        // Send JOIN_ACK to the new player (initial state)
        val ackPayload = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_JOIN_ACK)
            put(Protocol.FIELD_SESSION_ID, sid)
            put(Protocol.FIELD_HOST_NAME, hostName)
            put(Protocol.FIELD_PLAYERS, Protocol.buildJsonArray {
                sessionManager.getPlayers(sid).forEach { player ->
                    put(Protocol.buildJson {
                        put(Protocol.FIELD_NAME, player.name)
                        put(Protocol.FIELD_SCORE, player.score)
                        put(Protocol.FIELD_IS_HOST, player.isHost)
                    })
                }
            })
            val gameSession = sessionManager.getSession(sid)
            gameSession?.let { gs ->
                if (gs.currentVideoId != null) {
                    put(Protocol.FIELD_CURRENT_VIDEO_ID, gs.currentVideoId)
                    put(Protocol.FIELD_CURRENT_YEAR, gs.currentYear)
                    put(Protocol.FIELD_CURRENT_TITLE, gs.currentTitle)
                }
                put(Protocol.FIELD_CURRENT_PLAYER_INDEX, gs.currentPlayerIndex)
                put(Protocol.FIELD_CURRENT_PLAYER, sessionManager.getCurrentPlayer(sid) ?: "")
            }
        }

        connection.send(ackPayload.toString())
        networkListener?.onPlayerJoined(playerName, connection.remoteAddress)
        broadcastPlayerList()
    }

    // ── GUESS_BLIND Handling ─────────────────────────────────────────────────

    /**
     * Handle a blind guess from a remote client.
     * The guess is stored privately — NOT broadcasted to other clients.
     */
    private suspend fun handleGuessBlind(
        msg: org.json.JSONObject,
        connection: ClientConnection
    ) {
        val playerName = connection.playerName ?: msg.optString(Protocol.FIELD_PLAYER, "Unknown")
        val guess = msg.optInt(Protocol.FIELD_GUESS, 0)
        val sid = sessionId ?: return

        val stored = sessionManager.storeBlindGuess(sid, playerName, guess)
        if (stored) {
            Log.d(TAG, "Stored blind guess from $playerName: $guess")
        } else {
            Log.w(TAG, "Failed to store blind guess from $playerName")
        }
    }

    // ── END Handling ─────────────────────────────────────────────────────────

    private suspend fun handleEnd(msg: org.json.JSONObject, connection: ClientConnection) {
        val playerName = connection.playerName ?: msg.optString(Protocol.FIELD_PLAYER)
        Log.d(TAG, "End received from $playerName")
        connection.disconnect()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PUBLIC API — called from the host's UI fragment
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Broadcast a VIDEO message to all clients when a new video loads.
     */
    suspend fun broadcastVideo(videoId: String, year: Int, title: String) {
        val sid = sessionId ?: return
        sessionManager.setVideo(sid, videoId, year, title)

        val payload = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_VIDEO)
            put(Protocol.FIELD_VIDEO_ID, videoId)
            put(Protocol.FIELD_YEAR, year)
            put(Protocol.FIELD_TITLE, title)
        }
        broadcastToAll(payload.toString())
    }

    /**
     * Trigger the REVEAL round.
     *
     * Called when the host presses "Vis svar". This method:
     * 1. Stores the host's own local (same-device) guesses as blind guesses
     * 2. Sends REVEAL to all remote clients (they will respond with GUESS_BLIND)
     * 3. Waits a short period for pending GUESS_BLIND responses
     * 4. Computes all results and broadcasts REVEAL_RESULT
     *
     * @param localGuesses host-side player guesses: map of playerName → guessedYear
     * @param delayMs milliseconds to wait for remote GUESS_BLIND responses after REVEAL
     */
    suspend fun triggerReveal(
        localGuesses: Map<String, Int>,
        delayMs: Long = 1500L
    ) {
        val sid = sessionId ?: return

        // 1. Store host's local guesses as blind guesses too
        for ((name, guess) in localGuesses) {
            sessionManager.storeBlindGuess(sid, name, guess)
        }

        // 2. Send REVEAL to all remote clients
        val revealPayload = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_REVEAL)
        }
        broadcastToAll(revealPayload.toString())

        Log.d(TAG, "REVEAL sent, waiting ${delayMs}ms for GUESS_BLIND responses...")

        // 3. Wait for pending GUESS_BLIND responses to arrive
        delay(delayMs)

        // 4. Compute results and broadcast
        computeAndBroadcastRevealResults()
    }

    /**
     * Compute reveal results from stored blind guesses and broadcast REVEAL_RESULT.
     */
    private suspend fun computeAndBroadcastRevealResults() {
        val sid = sessionId ?: return
        val gameSession = sessionManager.getSession(sid) ?: return
        val correctYear = gameSession.currentYear ?: return

        // For host-side computing, we use a local ScoreManager.evaluateGuess
        // but don't use the singleton ScoreManager (which tracks single-player stats)
        val results = sessionManager.computeRevealResults(sid, Difficulty.MEDIUM)

        Log.d(TAG, "Computed ${results.size} reveal results (correct year=$correctYear)")

        // Build leaderboard from updated scores
        val leaderboard = sessionManager.getPlayers(sid).sortedByDescending { it.score }

        // Broadcast REVEAL_RESULT to all clients
        val resultPayload = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_REVEAL_RESULT)
            put(Protocol.FIELD_CORRECT_YEAR, correctYear)
            put(Protocol.FIELD_RESULTS, Protocol.buildJsonArray {
                results.forEach { r ->
                    put(Protocol.buildJson {
                        put(Protocol.FIELD_PLAYER, r.playerName)
                        put(Protocol.FIELD_GUESS, r.guess)
                        put(Protocol.FIELD_CORRECT_YEAR, r.correctYear)
                        put(Protocol.FIELD_POINTS_EARNED, r.pointsEarned)
                        put(Protocol.FIELD_DIFFERENCE, r.difference)
                        put(Protocol.FIELD_IS_CORRECT, r.isCorrect)
                        put(Protocol.FIELD_SCORE, r.totalScore)
                    })
                }
            })
            put(Protocol.FIELD_LEADERBOARD, Protocol.buildJsonArray {
                leaderboard.forEach { p ->
                    put(Protocol.buildJson {
                        put(Protocol.FIELD_NAME, p.name)
                        put(Protocol.FIELD_SCORE, p.score)
                        put(Protocol.FIELD_IS_HOST, p.isHost)
                    })
                }
            })
        }

        broadcastToAll(resultPayload.toString())

        // Also update local listeners with the results
        networkListener?.onRevealResultReceived(results)
        broadcastPlayerList()

        // Clear blind guesses for next round
        sessionManager.clearBlindGuesses(sid)

        Log.d(TAG, "REVEAL_RESULT broadcasted to all clients")
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BROADCAST METHODS
    // ══════════════════════════════════════════════════════════════════════════

    /** Send a JSON payload to every connected client. */
    private suspend fun broadcastToAll(json: String) {
        broadcastMutex.withLock {
            clients.values.toList().forEach { client ->
                client.send(json)
            }
        }
    }

    /** Broadcast updated player list to all clients. */
    private suspend fun broadcastPlayerList() {
        val sid = sessionId ?: return
        val payload = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_PLAYER_LIST)
            put(Protocol.FIELD_PLAYERS, Protocol.buildJsonArray {
                sessionManager.getPlayers(sid).forEach { player ->
                    put(Protocol.buildJson {
                        put(Protocol.FIELD_NAME, player.name)
                        put(Protocol.FIELD_SCORE, player.score)
                        put(Protocol.FIELD_IS_HOST, player.isHost)
                    })
                }
            })
        }
        broadcastToAll(payload.toString())
    }

    /** Broadcast that a player left. */
    private suspend fun broadcastPlayerLeft(playerName: String) {
        val payload = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_PLAYER_LEFT)
            put(Protocol.FIELD_PLAYER, playerName)
        }
        broadcastToAll(payload.toString())
    }

    /** Broadcast game END to all clients. */
    suspend fun broadcastEnd() {
        val payload = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_END)
        }
        broadcastToAll(payload.toString())
        networkListener?.onSessionEnded()
        serviceScope.launch {
            delay(2000)
            stopSelf()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SHUTDOWN
    // ══════════════════════════════════════════════════════════════════════════

    private fun shutdown() {
        Log.d(TAG, "Shutting down HostGameService")

        acceptJob?.cancel()
        acceptJob = null

        // Disconnect all clients
        clients.values.toList().forEach { it.disconnect() }
        clients.clear()

        // Close server socket
        when (val sock = serverSocket) {
            is ServerSocket -> {
                try { sock.close() } catch (_: IOException) {}
            }
            is BluetoothServerSocket -> {
                try { sock.close() } catch (_: IOException) {}
            }
        }
        serverSocket = null

        if (transport == Protocol.TRANSPORT_WIFI) {
            // Remove P2P group
            try {
                wifiChannel?.let { wifiP2pManager?.removeGroup(it, null) }
            } catch (_: Exception) {}

            // Unregister NSD
            try {
                nsdRegistrationListener?.let { nsdManager?.unregisterService(it) }
            } catch (_: Exception) {}
            nsdRegistrationListener = null

            // Unregister receiver
            if (receiverRegistered) {
                try { unregisterReceiver(wifiDirectReceiver) } catch (_: Exception) {}
                receiverRegistered = false
            }
        }

        // Remove session
        sessionId?.let { sessionManager.removeSession(it) }

        serviceScope.cancel()
        networkListener = null
    }
}
