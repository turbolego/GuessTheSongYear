package com.turbolego.songguesser

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
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
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Android Service that discovers and joins a hosted game session.
 *
 * Supports two transports:
 * - "wifi"  : NSD service discovery + WiFi Direct P2P connection + TCP socket
 * - "bluetooth" : Bluetooth device discovery + RFCOMM socket connection
 *
 * Protocol messages handled:
 *   JOIN_ACK, PLAYER_LIST, VIDEO, REVEAL, REVEAL_RESULT, GUESS_BLIND,
 *   PLAYER_LEFT, END
 *
 * Key new protocol flow:
 *   1. Receive VIDEO updates from the host.
 *   2. Receive REVEAL from host → send GUESS_BLIND with local player's guess.
 *   3. Receive REVEAL_RESULT with computed scores for all players.
 */
class JoinGameService : Service() {

    // ── Constants ───────────────────────────────────────────────────────────

    private val TAG = "JoinGameService"
    private val JSON_CHARSET = Charsets.UTF_8
    private val BT_UUID = UUID.fromString(Protocol.BT_SERVICE_UUID)

    // ── Companion & static API ──────────────────────────────────────────────

    companion object {
        @Volatile
        var instance: JoinGameService? = null

        private const val EXTRA_PLAYER_NAME = "player_name"
        private const val EXTRA_TRANSPORT = "transport"
        private const val EXTRA_HOST_DEVICE_ADDRESS = "host_device_address"

        /**
         * Start discovering + joining a game session.
         * @param transport "wifi" or "bluetooth"
         */
        fun startDiscovery(context: Context, playerName: String, transport: String = Protocol.TRANSPORT_WIFI) {
            val intent = Intent(context, JoinGameService::class.java).apply {
                putExtra(EXTRA_PLAYER_NAME, playerName)
                putExtra(EXTRA_TRANSPORT, transport)
                action = ACTION_DISCOVER
            }
            context.startService(intent)
        }

        /**
         * Start joining a specific host by device address.
         * For WiFi: the host's WiFi Direct device MAC address.
         * For Bluetooth: the host's Bluetooth MAC address.
         */
        fun joinHost(context: Context, playerName: String, hostDeviceAddress: String, transport: String = Protocol.TRANSPORT_WIFI) {
            val intent = Intent(context, JoinGameService::class.java).apply {
                putExtra(EXTRA_PLAYER_NAME, playerName)
                putExtra(EXTRA_TRANSPORT, transport)
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

    private var wifiP2pManager: WifiP2pManager? = null
    private var wifiChannel: WifiP2pManager.Channel? = null
    private var nsdManager: NsdManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socketJob: Job? = null

    /** Network connection — either a TCP Socket or BluetoothSocket. */
    private var gameSocket: Any? = null // Socket or BluetoothSocket
    private var writer: BufferedWriter? = null
    private val writeMutex = Mutex()
    private var reconnectAttempts = 0

    private var playerName: String = ""
    private var transport: String = Protocol.TRANSPORT_WIFI

    /** Host's device address (WiFi P2P MAC or Bluetooth MAC). */
    private var hostDeviceAddress: String? = null

    /** Host's IP address within P2P group (WiFi only). */
    private var hostAddress: java.net.InetAddress? = null

    /** Host's TCP port (WiFi only). */
    private var hostPort: Int = Protocol.WIFI_SERVER_PORT

    /** Current host display name. */
    private var hostName: String? = null

    /** Session ID assigned by host. */
    private var sessionId: String? = null

    /** Whether we successfully joined a session. */
    private var joined = false

    /** External listener for UI-layer events. */
    var networkListener: GameNetworkListener? = null

    /**
     * The local player's pending guess for the current round.
     * Set by the UI when the user changes their NumberPicker.
     * Sent as GUESS_BLIND when REVEAL is received.
     */
    var currentPendingGuess: Int = 2000

    // ── WiFi-specific state ─────────────────────────────────────────────────

    /** Queue of discovered services (avoid duplicates). */
    private val discoveredServices = ConcurrentLinkedQueue<NsdServiceInfo>()

    /** NSD listeners. */
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
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {}
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        Log.w(TAG, "WiFi Direct disabled")
                        networkListener?.onNetworkError("WiFi Direct is disabled")
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {}
            }
        }
    }

    private var receiverRegistered = false

    // ── Bluetooth Broadcast Receiver ────────────────────────────────────────

    /**
     * Receiver for Bluetooth device discovery results.
     * Only used when transport == "bluetooth".
     */
    private val bluetoothDiscoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null) {
                        Log.d(TAG, "Bluetooth device found: ${device.name} [${device.address}]")
                        onBluetoothDeviceFound(device)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d(TAG, "Bluetooth discovery finished")
                }
            }
        }
    }

    private var btDiscoveryReceiverRegistered = false

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        playerName = intent?.getStringExtra(EXTRA_PLAYER_NAME) ?: "Player"
        transport = intent?.getStringExtra(EXTRA_TRANSPORT) ?: Protocol.TRANSPORT_WIFI

        when (intent?.action) {
            ACTION_DISCOVER -> startDiscovery()
            ACTION_JOIN_HOST -> {
                hostDeviceAddress = intent.getStringExtra(EXTRA_HOST_DEVICE_ADDRESS)
                hostDeviceAddress?.let { address ->
                    connectToHost(address)
                } ?: run {
                    Log.e(TAG, "No host device address provided for ACTION_JOIN_HOST")
                    networkListener?.onNetworkError("No host device address provided")
                }
            }
            else -> {
                Log.d(TAG, "No action specified, starting discovery by default")
                startDiscovery()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        instance = null
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ══════════════════════════════════════════════════════════════════════════
    // DISCOVERY
    // ══════════════════════════════════════════════════════════════════════════

    private fun startDiscovery() {
        when (transport) {
            Protocol.TRANSPORT_BLUETOOTH -> startBluetoothDiscovery()
            else -> startWifiDiscovery()
        }
    }

    // ── Wi-Fi NSD Discovery ─────────────────────────────────────────────────

    private fun startWifiDiscovery() {
        Log.d(TAG, "Starting Wi-Fi NSD discovery")
        wifiP2pManager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        nsdManager = getSystemService(NSD_SERVICE) as NsdManager
        wifiChannel = wifiP2pManager?.initialize(this, mainLooper) { /* channel lost */ }
        registerWifiReceiver()
        discoveredServices.clear()

        val discListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "NSD discovery started: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "NSD service found: ${service.serviceName} (${service.serviceType})")
                if (service.serviceType == Protocol.WIFI_SERVICE_TYPE) {
                    if (!discoveredServices.contains(service)) {
                        discoveredServices.add(service)
                        resolveWifiService(service)
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
        this.discoveryListener = discListener

        try {
            nsdManager?.discoverServices(
                Protocol.WIFI_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discListener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NSD discovery", e)
            networkListener?.onNetworkError("Failed to discover game sessions: ${e.message}")
        }
    }

    private fun resolveWifiService(service: NsdServiceInfo) {
        val resListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD resolve failed: ${service.serviceName}, errorCode=$errorCode")
            }

            override fun onServiceResolved(service: NsdServiceInfo) {
                Log.d(TAG, "NSD service resolved: ${service.serviceName}")
                Log.d(TAG, "  Host: ${service.host?.hostAddress}, Port: ${service.port}")

                hostName = service.serviceName
                hostAddress = service.host
                hostPort = service.port

                // Extract TXT records
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

                // Initiate WiFi Direct connection
                if (hostDeviceAddress != null) {
                    connectToWifiHostP2p(hostDeviceAddress!!)
                } else {
                    Log.d(TAG, "No device address in TXT, trying direct socket")
                    serviceScope.launch {
                        connectWifiSocket()
                    }
                }
            }
        }
        this.resolveListener = resListener

        try {
            nsdManager?.resolveService(service, resListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve NSD service", e)
        }
    }

    private fun connectToWifiHostP2p(hostDeviceAddress: String) {
        Log.d(TAG, "Initiating P2P connection to device: $hostDeviceAddress")
        val ch = wifiChannel ?: run {
            Log.e(TAG, "WiFi channel is null")
            networkListener?.onNetworkError("WiFi Direct channel not available")
            return
        }

        val config = WifiP2pConfig().apply {
            deviceAddress = hostDeviceAddress
            groupOwnerIntent = 0 // We want the other device to be GO
        }

        wifiP2pManager?.connect(ch, config, object : WifiP2pManager.ActionListener {
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

    private fun onP2pConnected(p2pInfo: WifiP2pInfo) {
        Log.d(TAG, "P2P connected. Group owner: ${p2pInfo.groupOwnerAddress?.hostAddress}")

        if (p2pInfo.groupFormed && p2pInfo.groupOwnerAddress != null) {
            hostAddress = p2pInfo.groupOwnerAddress
            Log.d(TAG, "Host address from P2P info: ${hostAddress?.hostAddress}")
            serviceScope.launch {
                connectWifiSocket()
            }
        }
    }

    private fun registerWifiReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        @Suppress("DEPRECATION")
        registerReceiver(wifiDirectReceiver, filter, RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    // ── Bluetooth Discovery ─────────────────────────────────────────────────

    @Suppress("MissingPermission")
    private fun startBluetoothDiscovery() {
        Log.d(TAG, "Starting Bluetooth device discovery")
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            networkListener?.onNetworkError("Bluetooth is not supported on this device")
            return
        }
        if (!bluetoothAdapter!!.isEnabled) {
            networkListener?.onNetworkError("Bluetooth is not enabled")
            return
        }

        // Register for BT discovery results
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(bluetoothDiscoveryReceiver, filter, RECEIVER_EXPORTED)
        btDiscoveryReceiverRegistered = true

        // First, try paired devices that might be running the game
        val bondedDevices = bluetoothAdapter!!.bondedDevices
        if (bondedDevices != null) {
            for (device in bondedDevices) {
                Log.d(TAG, "Checking paired device: ${device.name} [${device.address}]")
                // Attempt connection to each paired device
                onBluetoothDeviceFound(device)
            }
        }

        // Start discovery for unpaired devices
        bluetoothAdapter!!.startDiscovery()
        Log.d(TAG, "Bluetooth discovery started")
    }

    @Suppress("MissingPermission")
    private fun onBluetoothDeviceFound(device: BluetoothDevice) {
        // Try connecting via RFCOMM using the game UUID
        Log.d(TAG, "Attempting Bluetooth connection to ${device.name} [${device.address}]")
        networkListener?.onServiceRegistered(device.name ?: device.address)

        serviceScope.launch {
            connectBluetoothSocket(device)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CONNECT TO HOST (shared entry for ACTION_JOIN_HOST)
    // ══════════════════════════════════════════════════════════════════════════

    private fun connectToHost(deviceAddress: String) {
        when (transport) {
            Protocol.TRANSPORT_BLUETOOTH -> {
                bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
                if (bluetoothAdapter == null) {
                    networkListener?.onNetworkError("Bluetooth not supported")
                    return
                }
                val device = bluetoothAdapter!!.getRemoteDevice(deviceAddress)
                serviceScope.launch {
                    connectBluetoothSocket(device)
                }
            }
            else -> {
                wifiP2pManager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
                wifiChannel = wifiP2pManager?.initialize(this, mainLooper) { }
                registerWifiReceiver()
                connectToWifiHostP2p(deviceAddress)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SOCKET CONNECTION
    // ══════════════════════════════════════════════════════════════════════════

    /** Connect a TCP socket to the WiFi host. */
    private suspend fun connectWifiSocket() {
        val host = hostAddress ?: run {
            Log.e(TAG, "No host address available")
            networkListener?.onNetworkError("No host address to connect to")
            return
        }

        socketJob?.cancel()
        socketJob = serviceScope.launch {
            try {
                Log.d(TAG, "Connecting TCP socket to ${host.hostAddress}:$hostPort")
                val socket = Socket()
                socket.connect(
                    InetSocketAddress(host, hostPort),
                    Protocol.CONNECT_TIMEOUT_MS
                )
                socket.soTimeout = Protocol.SOCKET_TIMEOUT_MS
                gameSocket = socket

                writer = BufferedWriter(
                    OutputStreamWriter(socket.getOutputStream(), JSON_CHARSET)
                )
                val reader = BufferedReader(
                    InputStreamReader(socket.getInputStream(), JSON_CHARSET)
                )

                reconnectAttempts = 0
                sendJoin()
                readLoop(reader)
            } catch (e: IOException) {
                Log.e(TAG, "TCP socket connection failed", e)
                networkListener?.onNetworkError("Connection to host failed: ${e.message}")
                attemptReconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Socket error", e)
                networkListener?.onNetworkError("Connection error: ${e.message}")
            }
        }
    }

    /** Connect a Bluetooth RFCOMM socket to the host device. */
    @Suppress("MissingPermission")
    private suspend fun connectBluetoothSocket(device: BluetoothDevice) {
        socketJob?.cancel()
        socketJob = serviceScope.launch {
            try {
                Log.d(TAG, "Connecting Bluetooth RFCOMM to ${device.name} [${device.address}]")
                val btSocket: BluetoothSocket =
                    device.createRfcommSocketToServiceRecord(BT_UUID)

                // Cancel BT discovery before connecting (faster connection)
                bluetoothAdapter?.cancelDiscovery()

                btSocket.connect()
                Log.d(TAG, "Bluetooth socket connected to ${device.name}")
                gameSocket = btSocket

                writer = BufferedWriter(
                    OutputStreamWriter(btSocket.outputStream, JSON_CHARSET)
                )
                val reader = BufferedReader(
                    InputStreamReader(btSocket.inputStream, JSON_CHARSET)
                )

                reconnectAttempts = 0
                hostName = device.name
                sendJoin()
                readLoop(reader)
            } catch (e: IOException) {
                Log.e(TAG, "Bluetooth connection failed", e)
                // Don't report network error for each failed BT attempt during discovery —
                // just log it. Many devices won't be running the game.
                Log.d(TAG, "Bluetooth connection rejected by ${device.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Bluetooth socket error", e)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // READ LOOP
    // ══════════════════════════════════════════════════════════════════════════

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

    // ══════════════════════════════════════════════════════════════════════════
    // MESSAGE DISPATCH
    // ══════════════════════════════════════════════════════════════════════════

    private fun dispatchMessage(json: String) {
        val msg = Protocol.tryParse(json) ?: run {
            Log.w(TAG, "Invalid JSON from host: $json")
            return
        }

        val type = msg.optString(Protocol.FIELD_TYPE)
        Log.d(TAG, "Received message type=$type")

        when (type) {
            Protocol.MSG_JOIN_ACK -> handleJoinAck(msg)
            Protocol.MSG_PLAYER_LIST -> handlePlayerList(msg)
            Protocol.MSG_VIDEO -> handleVideo(msg)
            Protocol.MSG_REVEAL -> handleReveal(msg)
            Protocol.MSG_REVEAL_RESULT -> handleRevealResult(msg)
            Protocol.MSG_END -> handleEnd(msg)
            Protocol.MSG_PLAYER_LEFT -> handlePlayerLeft(msg)
            else -> Log.w(TAG, "Unknown message type: $type")
        }
    }

    // ── Message Handlers ────────────────────────────────────────────────────

    private fun handleJoinAck(msg: org.json.JSONObject) {
        sessionId = msg.optString(Protocol.FIELD_SESSION_ID)
        hostName = msg.optString(Protocol.FIELD_HOST_NAME, "Host")
        joined = true

        Log.d(TAG, "Joined session: $sessionId (host: $hostName)")

        val gameSession = GameSessionManager.GameSession(
            sessionId = sessionId ?: "",
            hostName = hostName ?: "",
            players = mutableMapOf(),
            currentVideoId = msg.optString(Protocol.FIELD_CURRENT_VIDEO_ID, null),
            currentYear = if (msg.has(Protocol.FIELD_CURRENT_YEAR))
                msg.optInt(Protocol.FIELD_CURRENT_YEAR) else null,
            currentTitle = msg.optString(Protocol.FIELD_CURRENT_TITLE, null),
            currentPlayerIndex = msg.optInt(Protocol.FIELD_CURRENT_PLAYER_INDEX, 0),
            isHost = false
        )

        val playersArray = msg.optJSONArray(Protocol.FIELD_PLAYERS)
        if (playersArray != null) {
            for (i in 0 until playersArray.length()) {
                val playerObj = playersArray.getJSONObject(i)
                val pName = playerObj.optString(Protocol.FIELD_NAME, "Unknown")
                val pScore = playerObj.optInt(Protocol.FIELD_SCORE, 0)
                val pIsHost = playerObj.optBoolean(Protocol.FIELD_IS_HOST, false)
                gameSession.players[pName] = GameSessionManager.GameSession.PlayerInfo(
                    name = pName,
                    score = pScore,
                    isHost = pIsHost
                )
            }
        }

        networkListener?.onJoinedSession(gameSession)
    }

    private fun handlePlayerList(msg: org.json.JSONObject) {
        val playersArray = msg.optJSONArray(Protocol.FIELD_PLAYERS)
        Log.d(TAG, "Player list updated")
        if (playersArray != null) {
            for (i in 0 until playersArray.length()) {
                val playerObj = playersArray.getJSONObject(i)
                Log.d(TAG, "  ${playerObj.optString(Protocol.FIELD_NAME)}: " +
                        "${playerObj.optInt(Protocol.FIELD_SCORE)}")
            }
        }
    }

    private fun handleVideo(msg: org.json.JSONObject) {
        val videoId = msg.optString(Protocol.FIELD_VIDEO_ID, "")
        val year = msg.optInt(Protocol.FIELD_YEAR, 0)
        val title = msg.optString(Protocol.FIELD_TITLE, "")

        Log.d(TAG, "Video update: $title ($year) id=$videoId")
        networkListener?.onVideoReceived(videoId, year, title)
    }

    /**
     * Handle REVEAL from host.
     * The host has pressed "Vis svar" — send our blind guess back immediately.
     */
    private fun handleReveal(msg: org.json.JSONObject) {
        Log.d(TAG, "REVEAL received from host — sending GUESS_BLIND")
        networkListener?.onRevealReceived()

        // Automatically submit the pending guess
        serviceScope.launch {
            sendGuessBlind(currentPendingGuess)
        }
    }

    /**
     * Handle REVEAL_RESULT from host.
     * Contains computed scores and leaderboard for all players.
     */
    private fun handleRevealResult(msg: org.json.JSONObject) {
        val correctYear = msg.optInt(Protocol.FIELD_CORRECT_YEAR, 0)

        // Parse individual results
        val results = mutableListOf<GameSessionManager.RevealResult>()
        val resultsArray = msg.optJSONArray(Protocol.FIELD_RESULTS)
        if (resultsArray != null) {
            for (i in 0 until resultsArray.length()) {
                val r = resultsArray.getJSONObject(i)
                results.add(
                    GameSessionManager.RevealResult(
                        playerName = r.optString(Protocol.FIELD_PLAYER, ""),
                        guess = r.optInt(Protocol.FIELD_GUESS, 0),
                        correctYear = r.optInt(Protocol.FIELD_CORRECT_YEAR, correctYear),
                        pointsEarned = r.optInt(Protocol.FIELD_POINTS_EARNED, 0),
                        difference = r.optInt(Protocol.FIELD_DIFFERENCE, 0),
                        isCorrect = r.optBoolean(Protocol.FIELD_IS_CORRECT, false),
                        totalScore = r.optInt(Protocol.FIELD_SCORE, 0)
                    )
                )
            }
        }

        Log.d(TAG, "REVEAL_RESULT received: ${results.size} players")
        networkListener?.onRevealResultReceived(results)
    }

    private fun handleEnd(msg: org.json.JSONObject) {
        Log.d(TAG, "Game ended by host")
        joined = false
        networkListener?.onSessionEnded()
        serviceScope.launch {
            delay(1000)
            stopSelf()
        }
    }

    private fun handlePlayerLeft(msg: org.json.JSONObject) {
        val player = msg.optString(Protocol.FIELD_PLAYER, "")
        Log.d(TAG, "Player left: $player")
        networkListener?.onPlayerDisconnected(player)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // OUTGOING MESSAGES
    // ══════════════════════════════════════════════════════════════════════════

    /** Send the JOIN message to the host. */
    private suspend fun sendJoin() {
        val payload = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_JOIN)
            put(Protocol.FIELD_PLAYER, playerName)
        }
        sendMessage(payload.toString())
    }

    /**
     * Send a GUESS_BLIND message to the host.
     * This is called automatically when REVEAL is received,
     * and can also be called from the UI directly.
     */
    suspend fun sendGuessBlind(guess: Int) {
        if (!joined) {
            Log.w(TAG, "Cannot send GUESS_BLIND: not joined to a session")
            return
        }
        val payload = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_GUESS_BLIND)
            put(Protocol.FIELD_PLAYER, playerName)
            put(Protocol.FIELD_GUESS, guess)
        }
        sendMessage(payload.toString())
        Log.d(TAG, "GUESS_BLIND sent: $guess")
    }

    /**
     * Send an END message to the host indicating the local player is leaving.
     */
    suspend fun sendLeave() {
        if (!joined) return
        val payload = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_END)
            put(Protocol.FIELD_PLAYER, playerName)
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

    // ══════════════════════════════════════════════════════════════════════════
    // RECONNECTION
    // ══════════════════════════════════════════════════════════════════════════

    private fun attemptReconnect() {
        if (reconnectAttempts >= Protocol.MAX_RECONNECT_ATTEMPTS || joined) return
        reconnectAttempts++
        Log.d(TAG, "Reconnection attempt $reconnectAttempts/${Protocol.MAX_RECONNECT_ATTEMPTS}")
        serviceScope.launch {
            delay(Protocol.RECONNECT_DELAY_MS)
            when (transport) {
                Protocol.TRANSPORT_BLUETOOTH -> {
                    // Bluetooth reconnect isn't straightforward without re-discovery
                    networkListener?.onNetworkError("Bluetooth connection lost")
                }
                else -> connectWifiSocket()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SHUTDOWN
    // ══════════════════════════════════════════════════════════════════════════

    private fun disconnectSocket() {
        try {
            writer?.close()
        } catch (_: IOException) {}
        writer = null

        when (val sock = gameSocket) {
            is Socket -> {
                try { sock.close() } catch (_: IOException) {}
            }
            is BluetoothSocket -> {
                try { sock.close() } catch (_: IOException) {}
            }
        }
        gameSocket = null
    }

    private fun shutdown() {
        Log.d(TAG, "Shutting down JoinGameService")
        socketJob?.cancel()
        socketJob = null
        disconnectSocket()

        if (transport == Protocol.TRANSPORT_WIFI) {
            // Stop NSD discovery
            try {
                discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
            } catch (_: Exception) {}
            discoveryListener = null
            resolveListener = null

            // Disconnect P2P
            try {
                wifiChannel?.let { wifiP2pManager?.removeGroup(it, null) }
            } catch (_: Exception) {}

            // Unregister receiver
            if (receiverRegistered) {
                try { unregisterReceiver(wifiDirectReceiver) } catch (_: Exception) {}
                receiverRegistered = false
            }
        } else {
            // Bluetooth cleanup
            try {
                bluetoothAdapter?.cancelDiscovery()
            } catch (_: Exception) {}
            if (btDiscoveryReceiverRegistered) {
                try { unregisterReceiver(bluetoothDiscoveryReceiver) } catch (_: Exception) {}
                btDiscoveryReceiverRegistered = false
            }
        }

        serviceScope.cancel()
        discoveredServices.clear()
        joined = false
        networkListener = null
    }
}
