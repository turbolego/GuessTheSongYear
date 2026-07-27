package com.turbolego.songguesser

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Simple TCP server hosting a GuessTheSongYear multiplayer game.
 *
 * Transport modes:
 * - "wifi":    TCP ServerSocket on the device's local WiFi IP, port [WIFI_SERVER_PORT].
 *              Join by entering the displayed IP:port in JoinGameFragment.
 * - "bluetooth": Bluetooth RFCOMM server (existing implementation).
 *
 * Protocol: newline-delimited JSON (see [Protocol]).
 */
class HostGameService : Service() {

    private val TAG = "HostGameService"
    private val BT_UUID = UUID.fromString(Protocol.BT_SERVICE_UUID)

    companion object {
        @Volatile
        var instance: HostGameService? = null

        @Volatile
        var pendingListener: GameNetworkListener? = null

        private const val EXTRA_PLAYER_NAME = "player_name"
        private const val EXTRA_TRANSPORT = "transport"

        /** Maximum simultaneous TCP clients. */
        const val MAX_CLIENTS = 8

        /** Maximum bytes per line in protocol messages. */
        const val MAX_MESSAGE_LENGTH = 8 * 1024

        fun start(context: Context, playerName: String, transport: String = Protocol.TRANSPORT_WIFI) {
            val intent = Intent(context, HostGameService::class.java).apply {
                putExtra(EXTRA_PLAYER_NAME, playerName)
                putExtra(EXTRA_TRANSPORT, transport)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HostGameService::class.java))
        }
    }

    // ── State ────────────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var tlsServerSocket: SSLServerSocket? = null
    private var btServerSocket: BluetoothServerSocket? = null
    private var hostCredentials: SecureChannelManager.HostCredentials? = null
    private var acceptJob: Job? = null
    private val clients = ConcurrentHashMap<Int, ClientConnection>()
    private val playerConnections = ConcurrentHashMap<String, Int>()
    private val nextConnId = AtomicInteger(1)
    private val broadcastMutex = Mutex()
    private val sessionManager = GameSessionManager()
    private var sessionId: String? = null
    private var hostName: String = "Host"
    private var transport: String = Protocol.TRANSPORT_WIFI
    private var hostIp: String? = null
    private var hostPort: Int = Protocol.WIFI_SERVER_PORT
    private var sessionKey: String? = null       // shared HMAC key for message signing
    private var auth: Protocol.MessageAuthenticator? = null

    var networkListener: GameNetworkListener? = null

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "onCreate")
        pendingListener?.let {
            networkListener = it
            pendingListener = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")

        hostName = intent?.getStringExtra(EXTRA_PLAYER_NAME) ?: "Host"
        transport = intent?.getStringExtra(EXTRA_TRANSPORT) ?: Protocol.TRANSPORT_WIFI

        try {
            startHosting()
        } catch (e: Exception) {
            Log.e(TAG, "CRASH in startHosting", e)
            networkListener?.onNetworkError("Krasj: ${e::class.simpleName}: ${e.message}")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        instance = null
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Hosting ──────────────────────────────────────────────────────────

    private fun startHosting() {
        Log.d(TAG, "startHosting transport=$transport")
        sessionId = "${hostName}_${System.currentTimeMillis()}"
        sessionManager.createSession(sessionId!!, hostName)
        networkListener?.onHostingStatus("Oppretter spill-økt...")

        // Generate session key for HMAC message signing
        sessionKey = Protocol.generateSessionKey()
        auth = sessionKey?.let { Protocol.createAuthenticator(it) }

        when (transport) {
            Protocol.TRANSPORT_BLUETOOTH -> startBluetooth()
            else -> startWifi()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Wi-Fi TRANSPORT — plain TCP ServerSocket on local WiFi IP
    // ═══════════════════════════════════════════════════════════════════════

    private fun startWifi() {
        Log.d(TAG, "startWifi")

        // 1) Check WiFi is enabled (but not strictly required — any IP works)
        val wm = getSystemService(WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) {
            networkListener?.onNetworkError("WiFi er ikke aktivert (skru på WiFi for spilling)")
            return
        }

        // 2) Find local WiFi IP
        hostIp = getWifiIpAddress() ?: run {
            networkListener?.onNetworkError("Fant ikke lokal IP — sjekk at du er koblet til WiFi")
            return
        }

        // 3) Generate TLS host credentials
        try {
            hostCredentials = SecureChannelManager.createHostCredentials()
            networkListener?.onHostingStatus("SPKI: ${hostCredentials!!.spkiHash}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create TLS credentials", e)
            // Non-fatal: continue with plain TCP only
            networkListener?.onNetworkError("TLS-feil: ${e.message} (kjører ukryptert)")
        }

        networkListener?.onHostingStatus("IP: $hostIp")

        // 4) Start plain TCP server socket on background thread
        acceptJob?.cancel()
        acceptJob = serviceScope.launch {
            try {
                // ── Plain TCP socket on WIFI_SERVER_PORT ──
                val sock = ServerSocket()
                sock.reuseAddress = true
                sock.bind(InetSocketAddress(hostPort))
                serverSocket = sock
                Log.d(TAG, "TCP server listening on $hostIp:$hostPort")
                networkListener?.onHostingStatus("Server på $hostIp:$hostPort")

                // ── TLS socket on WIFI_TLS_SERVER_PORT (if creds available) ──
                val creds = hostCredentials
                if (creds != null) {
                    try {
                        val tlsSock = SecureChannelManager.createSecureServerSocket(
                            creds.sslContext, Protocol.WIFI_TLS_SERVER_PORT
                        )
                        tlsServerSocket = tlsSock
                        Log.d(TAG, "TLS server listening on $hostIp:${Protocol.WIFI_TLS_SERVER_PORT}")
                        // Launch TLS accept loop in parallel
                        launch { acceptTlsClients(tlsSock) }
                    } catch (e: IOException) {
                        Log.e(TAG, "Failed to start TLS server", e)
                        networkListener?.onNetworkError("TLS-server-feil: ${e.message}")
                    }
                }

                networkListener?.onHostingStarted(sessionId ?: "unknown", hostName)
                acceptClients(sock)
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start TCP server", e)
                networkListener?.onNetworkError("Kunne ikke starte server: ${e.message}")
            }
        }
    }

    /** Find the device's local WiFi IPv4 address. */
    private fun getWifiIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                // Prefer wlan0 / wlan interfaces; fall back to any non-loopback
                val isWifi = iface.name.startsWith("wlan") || iface.name.startsWith("eth")
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        if (isWifi) return addr.hostAddress
                    }
                }
            }
            // Fallback: first non-loopback IPv4
            val interfaces2 = NetworkInterface.getNetworkInterfaces()
            while (interfaces2.hasMoreElements()) {
                val iface = interfaces2.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getWifiIpAddress error", e)
        }
        return null
    }

    private suspend fun acceptClients(sock: ServerSocket) {
        try {
            while (serviceScope.isActive) {
                if (clients.size >= MAX_CLIENTS) {
                    // Reject connection when at capacity
                    try {
                        val reject = sock.accept()
                        reject.close()
                    } catch (_: IOException) { }
                    delay(500)
                    continue
                }
                val client = sock.accept() ?: break
                serviceScope.launch {
                    handleClient(client)
                }
            }
        } catch (e: IOException) {
            if (e !is SocketException || !sock.isClosed) {
                Log.e(TAG, "Accept error", e)
            }
        }
    }

    /** Accept loop for TLS-secured clients on the dedicated TLS port. */
    private suspend fun acceptTlsClients(sock: SSLServerSocket) {
        try {
            while (serviceScope.isActive) {
                if (clients.size >= MAX_CLIENTS) {
                    try {
                        val reject = sock.accept() as SSLSocket
                        reject.close()
                    } catch (_: IOException) { }
                    delay(500)
                    continue
                }
                val client = sock.accept() as SSLSocket ?: break
                Log.d(TAG, "TLS client connected: ${client.inetAddress}")
                serviceScope.launch {
                    handleClient(client)
                }
            }
        } catch (e: IOException) {
            if (e !is SocketException || !sock.isClosed) {
                Log.e(TAG, "TLS accept error", e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CLIENT HANDLING (shared between Wi-Fi and Bluetooth)
    // ═══════════════════════════════════════════════════════════════════════

    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = PrintWriter(socket.getOutputStream(), true)

            // Read the first message — must be JOIN or HELLO
            val firstLine = readLineBounded(reader) ?: return
            val firstMsg = Protocol.tryParse(firstLine) ?: run { return }

            val firstType = firstMsg.optString(Protocol.FIELD_TYPE)

            // HELLO: respond immediately with host info, then close
            if (firstType == Protocol.MSG_HELLO) {
                val ackMsg = Protocol.buildJson {
                    put(Protocol.FIELD_TYPE, Protocol.MSG_ACK)
                    put(Protocol.FIELD_HOST_NAME, hostName)
                    put(Protocol.FIELD_PLAYERS, JSONArray(getAllPlayerNames()))
                }
                writer.println(ackMsg.toString())
                try { socket.close() } catch (_: IOException) {}
                return
            }

            // Not JOIN or HELLO — bail
            if (firstType != Protocol.MSG_JOIN) return

            val connId = nextConnId.getAndIncrement()
            val playerName = firstMsg.optString(Protocol.FIELD_PLAYER, "Ukjent")

            // Close existing connection for same player name
            playerConnections.remove(playerName)?.let { oldConnId ->
                clients.remove(oldConnId)?.disconnect()
            }

            val conn = ClientConnection(connId, playerName, reader, writer, socket)
            clients[connId] = conn
            playerConnections[playerName] = connId

            // Send JOIN_ACK
            val players = getAllPlayerNames()
            val ack = Protocol.buildJson {
                put(Protocol.FIELD_TYPE, Protocol.MSG_JOIN_ACK)
                put(Protocol.FIELD_SESSION_ID, sessionId ?: "")
                put(Protocol.FIELD_HOST_NAME, hostName)
                put(Protocol.FIELD_SESSION_KEY, sessionKey ?: "")
                put(Protocol.FIELD_PLAYERS, JSONArray(players))
            }
            writer.println(ack.toString())
            broadcastPlayerList()

            // Read further messages in a loop
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.length > MAX_MESSAGE_LENGTH) {
                    conn.disconnect()
                    break
                }
                val msg = Protocol.tryParse(line!!) ?: continue
                handleMessage(playerName, msg, writer)
            }
        } catch (e: IOException) {
            // client disconnected
        } catch (e: Exception) {
            // unexpected error
        } finally {
            val disconnectedId = clients.entries.find { it.value.socket == socket }?.key
            if (disconnectedId != null) {
                val disconnectedPlayer = clients[disconnectedId]?.player
                clients.remove(disconnectedId)
                if (disconnectedPlayer != null) {
                    playerConnections.remove(disconnectedPlayer, disconnectedId)
                }
                disconnectedPlayer?.let { networkListener?.onPlayerDisconnected(it) }
                broadcastPlayerList()
            }
            try { socket.close() } catch (_: IOException) {}
        }
    }

    /** Read a line from the buffered reader, rejecting oversized lines. */
    private fun readLineBounded(reader: BufferedReader): String? {
        var charCount = 0
        val buf = StringBuilder(256)
        while (true) {
            val c = reader.read()
            if (c == -1) return null
            if (c == 10) return buf.toString() // '\n'
            if (c != 13) { // '\r'
                charCount++
                if (charCount > MAX_MESSAGE_LENGTH) return null
                buf.append(c.toChar())
            }
        }
    }

    /** Get all connected player names including the host. */
    private fun getAllPlayerNames(): List<String> {
        return listOf(hostName) + clients.values.map { it.player }
    }

    /** Used by Bluetooth path — wraps a BluetoothSocket in a stream pair. */
    private fun handleBluetoothClient(btSocket: BluetoothSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(btSocket.inputStream, Charsets.UTF_8))
            val writer = PrintWriter(btSocket.outputStream, true)

            val firstLine = readLineBounded(reader) ?: return
            val firstMsg = Protocol.tryParse(firstLine) ?: return

            val firstType = firstMsg.optString(Protocol.FIELD_TYPE)

            // HELLO: respond immediately with host info, then close
            if (firstType == Protocol.MSG_HELLO) {
                val ackMsg = Protocol.buildJson {
                    put(Protocol.FIELD_TYPE, Protocol.MSG_ACK)
                    put(Protocol.FIELD_HOST_NAME, hostName)
                    put(Protocol.FIELD_PLAYERS, JSONArray(getAllPlayerNames()))
                }
                writer.println(ackMsg.toString())
                try { btSocket.close() } catch (_: IOException) {}
                return
            }

            // Not JOIN — bail
            if (firstType != Protocol.MSG_JOIN) return

            val connId = nextConnId.getAndIncrement()
            val playerName = firstMsg.optString(Protocol.FIELD_PLAYER, "Ukjent")

            // Close existing connection for same player name
            playerConnections.remove(playerName)?.let { oldConnId ->
                clients.remove(oldConnId)?.disconnect()
            }

            val conn = ClientConnection(connId, playerName, reader, writer, null)
            clients[connId] = conn
            playerConnections[playerName] = connId

            val ack = Protocol.buildJson {
                put(Protocol.FIELD_TYPE, Protocol.MSG_JOIN_ACK)
                put(Protocol.FIELD_SESSION_ID, sessionId ?: "")
                put(Protocol.FIELD_HOST_NAME, hostName)
                put(Protocol.FIELD_SESSION_KEY, sessionKey ?: "")
                put(Protocol.FIELD_PLAYERS, JSONArray(getAllPlayerNames()))
            }
            writer.println(ack.toString())
            broadcastPlayerList()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.length > MAX_MESSAGE_LENGTH) {
                    conn.disconnect()
                    break
                }
                val msg = Protocol.tryParse(line!!) ?: continue
                handleMessage(playerName, msg, writer)
            }
        } catch (e: IOException) {
            // client disconnected
        } catch (_: Exception) {}
    }

    private fun handleMessage(playerName: String, msg: JSONObject, writer: PrintWriter) {
        // Verify HMAC signature if auth is available
        if (auth != null && !auth!!.verify(msg)) {
            Log.w(TAG, "Dropped message from $playerName: invalid HMAC signature")
            return
        }
        when (msg.optString(Protocol.FIELD_TYPE)) {
            Protocol.MSG_GUESS_BLIND -> {
                val guess = msg.optInt(Protocol.FIELD_GUESS, 0)
                sessionManager.storeBlindGuess(sessionId ?: "", playerName, guess)
                Log.d(TAG, "Blind guess from $playerName: $guess")
            }
            Protocol.MSG_END -> {
                Log.d(TAG, "Client $playerName ended session")
                broadcastEnd()
            }
        }
    }

    // ── Broadcast helpers (host → all clients) ──────────────────────────

    private fun broadcastPlayerList() {
        val msg = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_PLAYER_LIST)
            put(Protocol.FIELD_PLAYERS, JSONArray(getAllPlayerNames()))
        }
        auth?.sign(msg)
        broadcastToAll(msg.toString())
    }

    fun broadcastVideo(videoId: String, year: Int, title: String) {
        val msg = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_VIDEO)
            put(Protocol.FIELD_VIDEO_ID, videoId)
            put(Protocol.FIELD_YEAR, year)
            put(Protocol.FIELD_TITLE, title)
        }
        auth?.sign(msg)
        Log.d(TAG, "Broadcasting VIDEO: $videoId ($year)")
        broadcastToAll(msg.toString())
    }

    fun triggerReveal(localGuesses: Map<String, Int>) {
        // Store host's local guesses as blind guesses
        for ((player, guess) in localGuesses) {
            sessionManager.storeBlindGuess(sessionId ?: "", player, guess)
        }
        broadcastReveal()
    }

    fun broadcastReveal() {
        val results = sessionManager.computeRevealResults(sessionId ?: "", Difficulty.MEDIUM)
        val msg = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_REVEAL_RESULT)
            put(Protocol.FIELD_RESULTS, JSONArray(results.map { r ->
                JSONObject().apply {
                    put(Protocol.FIELD_PLAYER, r.playerName)
                    put(Protocol.FIELD_GUESS, r.guess)
                    put(Protocol.FIELD_CORRECT_YEAR, r.correctYear)
                    put(Protocol.FIELD_POINTS_EARNED, r.pointsEarned)
                    put(Protocol.FIELD_DIFFERENCE, r.difference)
                    put(Protocol.FIELD_IS_CORRECT, r.isCorrect)
                    put(Protocol.FIELD_TOTAL_SCORE, r.totalScore)
                }
            }))
        }
        auth?.sign(msg)
        Log.d(TAG, "Broadcasting REVEAL_RESULT")
        broadcastToAll(msg.toString())
        // Also send REVEAL for clients still in old flow
        val reveal = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_REVEAL)
        }
        auth?.sign(reveal)
        broadcastToAll(reveal.toString())
    }

    private fun broadcastEnd() {
        val msg = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_END)
        }
        auth?.sign(msg)
        broadcastToAll(msg.toString())
    }

    private fun broadcastToAll(json: String) {
        serviceScope.launch {
            broadcastMutex.withLock {
                clients.values.forEach { conn ->
                    try {
                        conn.writer?.println(json)
                    } catch (e: Exception) {
                        Log.w(TAG, "Broadcast to ${conn.player} failed", e)
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BLUETOOTH TRANSPORT
    // ═══════════════════════════════════════════════════════════════════════

    private fun startBluetooth() {
        Log.d(TAG, "startBluetooth")
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            networkListener?.onNetworkError("Bluetooth støttes ikke på denne enheten")
            return
        }
        if (!adapter.isEnabled) {
            networkListener?.onNetworkError("Bluetooth er ikke slått på")
            return
        }

        acceptJob?.cancel()
        acceptJob = serviceScope.launch {
            try {
                val btSock = adapter.listenUsingRfcommWithServiceRecord(
                    Protocol.BT_SERVICE_NAME, BT_UUID
                )
                btServerSocket = btSock
                Log.d(TAG, "Bluetooth server listening")
                networkListener?.onHostingStatus("Bluetooth-server aktiv")
                networkListener?.onHostingStarted(sessionId ?: "unknown", hostName)
                acceptBluetoothClients(btSock)
            } catch (e: IOException) {
                Log.e(TAG, "Bluetooth server failed", e)
                networkListener?.onNetworkError("Bluetooth-feil: ${e.message}")
            }
        }
    }

    private suspend fun acceptBluetoothClients(btSock: BluetoothServerSocket) {
        try {
            while (serviceScope.isActive) {
                val client = btSock.accept() ?: break
                val deviceName = client.remoteDevice?.name ?: "ukjent"
                Log.d(TAG, "BT client: $deviceName")
                serviceScope.launch {
                    handleBluetoothClient(client)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "BT accept loop error", e)
        }
    }

    // ── Shutdown ─────────────────────────────────────────────────────────

    private fun shutdown() {
        Log.d(TAG, "shutdown")

        acceptJob?.cancel()
        acceptJob = null

        clients.values.toList().forEach { it.disconnect() }
        clients.clear()

        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        try { tlsServerSocket?.close() } catch (_: IOException) {}
        tlsServerSocket = null
        try { btServerSocket?.close() } catch (_: IOException) {}
        btServerSocket = null

        sessionId?.let { sessionManager.removeSession(it) }
        serviceScope.cancel()
        networkListener = null
    }

    // ── ClientConnection inner class ─────────────────────────────────────

    private inner class ClientConnection(
        val connId: Int,
        val player: String,
        val reader: BufferedReader?,
        val writer: PrintWriter?,
        val socket: Socket?,
    ) {
        fun disconnect() {
            playerConnections.remove(player, connId)
            clients.remove(connId)
            try { socket?.close() } catch (_: IOException) {}
        }
    }
}
