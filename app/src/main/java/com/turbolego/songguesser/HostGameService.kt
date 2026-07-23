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
import java.util.concurrent.ConcurrentHashMap

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
    private var btServerSocket: BluetoothServerSocket? = null
    private var acceptJob: Job? = null
    private val clients = ConcurrentHashMap<String, ClientConnection>()
    private val broadcastMutex = Mutex()
    private val sessionManager = GameSessionManager()
    private var sessionId: String? = null
    private var hostName: String = "Host"
    private var transport: String = Protocol.TRANSPORT_WIFI
    private var hostIp: String? = null
    private var hostPort: Int = Protocol.WIFI_SERVER_PORT

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

        networkListener?.onHostingStatus("IP: $hostIp")

        // 3) Start TCP server socket on background thread
        acceptJob?.cancel()
        acceptJob = serviceScope.launch {
            try {
                val sock = ServerSocket()
                sock.reuseAddress = true
                sock.bind(InetSocketAddress(hostPort))
                serverSocket = sock
                Log.d(TAG, "TCP server listening on $hostIp:$hostPort")
                networkListener?.onHostingStatus("Server på $hostIp:$hostPort")
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
                val client = sock.accept() ?: break
                Log.d(TAG, "Client connected: ${client.inetAddress.hostAddress}")
                networkListener?.onHostingStatus("Ny klient: ${client.inetAddress.hostAddress}")
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

    // ═══════════════════════════════════════════════════════════════════════
    // CLIENT HANDLING (shared between Wi-Fi and Bluetooth)
    // ═══════════════════════════════════════════════════════════════════════

    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = PrintWriter(socket.getOutputStream(), true)

            // Read the first message — must be JOIN
            val joinLine = reader.readLine() ?: return
            val joinMsg = Protocol.tryParse(joinLine) ?: run {
                Log.w(TAG, "Invalid join message: $joinLine")
                return
            }
            if (joinMsg.optString(Protocol.FIELD_TYPE) != Protocol.MSG_JOIN) {
                Log.w(TAG, "First message was not JOIN: ${joinMsg.optString(Protocol.FIELD_TYPE)}")
                return
            }

            val playerName = joinMsg.optString(Protocol.FIELD_PLAYER, "Ukjent")
            val conn = ClientConnection(playerName, reader, writer, socket)
            clients[playerName] = conn

            Log.d(TAG, "Player joined: $playerName")
            networkListener?.onPlayerJoined(playerName, socket.inetAddress.hostAddress ?: "")

            // Send JOIN_ACK
            val players = listOf(hostName) + clients.keys.toList()
            val ack = Protocol.buildJson {
                put(Protocol.FIELD_TYPE, Protocol.MSG_JOIN_ACK)
                put(Protocol.FIELD_SESSION_ID, sessionId ?: "")
                put(Protocol.FIELD_HOST_NAME, hostName)
                put(Protocol.FIELD_PLAYERS, JSONArray(players))
            }
            writer.println(ack.toString())
            broadcastPlayerList()

            // Read further messages in a loop
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val msg = Protocol.tryParse(line!!) ?: continue
                handleMessage(playerName, msg, writer)
            }
        } catch (e: IOException) {
            Log.d(TAG, "Client disconnected: ${socket.inetAddress.hostAddress}")
        } catch (e: Exception) {
            Log.e(TAG, "handleClient error", e)
        } finally {
            val disconnectedPlayer = clients.entries.find { it.value.socket == socket }?.key
            if (disconnectedPlayer != null) {
                clients.remove(disconnectedPlayer)
                networkListener?.onPlayerDisconnected(disconnectedPlayer)
                broadcastPlayerList()
            }
            try { socket.close() } catch (_: IOException) {}
        }
    }

    /** Used by Bluetooth path — wraps a BluetoothSocket in a stream pair. */
    private fun handleBluetoothClient(btSocket: BluetoothSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(btSocket.inputStream, Charsets.UTF_8))
            val writer = PrintWriter(btSocket.outputStream, true)

            val joinLine = reader.readLine() ?: return
            val joinMsg = Protocol.tryParse(joinLine) ?: return
            if (joinMsg.optString(Protocol.FIELD_TYPE) != Protocol.MSG_JOIN) return

            val playerName = joinMsg.optString(Protocol.FIELD_PLAYER, "Ukjent")
            val conn = ClientConnection(playerName, reader, writer, null)
            clients[playerName] = conn

            Log.d(TAG, "BT player joined: $playerName")
            networkListener?.onPlayerJoined(playerName, btSocket.remoteDevice?.address ?: "")

            val players = listOf(hostName) + clients.keys.toList()
            val ack = Protocol.buildJson {
                put(Protocol.FIELD_TYPE, Protocol.MSG_JOIN_ACK)
                put(Protocol.FIELD_SESSION_ID, sessionId ?: "")
                put(Protocol.FIELD_HOST_NAME, hostName)
                put(Protocol.FIELD_PLAYERS, JSONArray(players))
            }
            writer.println(ack.toString())
            broadcastPlayerList()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val msg = Protocol.tryParse(line!!) ?: continue
                handleMessage(playerName, msg, writer)
            }
        } catch (e: IOException) {
            Log.d(TAG, "BT client disconnected")
        } catch (_: Exception) {}
    }

    private fun handleMessage(playerName: String, msg: JSONObject, writer: PrintWriter) {
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
        val players = listOf(hostName) + clients.keys.toList()
        val msg = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_PLAYER_LIST)
            put(Protocol.FIELD_PLAYERS, JSONArray(players))
        }.toString()
        broadcastToAll(msg)
    }

    fun broadcastVideo(videoId: String, year: Int, title: String) {
        val msg = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_VIDEO)
            put(Protocol.FIELD_VIDEO_ID, videoId)
            put(Protocol.FIELD_YEAR, year)
            put(Protocol.FIELD_TITLE, title)
        }.toString()
        Log.d(TAG, "Broadcasting VIDEO: $videoId ($year)")
        broadcastToAll(msg)
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
        }.toString()
        Log.d(TAG, "Broadcasting REVEAL_RESULT")
        broadcastToAll(msg)
        // Also send REVEAL for clients still in old flow
        val reveal = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_REVEAL)
        }.toString()
        broadcastToAll(reveal)
    }

    private fun broadcastEnd() {
        val msg = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_END)
        }.toString()
        broadcastToAll(msg)
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
        try { btServerSocket?.close() } catch (_: IOException) {}
        btServerSocket = null

        sessionId?.let { sessionManager.removeSession(it) }
        serviceScope.cancel()
        networkListener = null
    }

    // ── ClientConnection inner class ─────────────────────────────────────

    private inner class ClientConnection(
        val player: String,
        val reader: BufferedReader?,
        val writer: PrintWriter?,
        val socket: Socket?,
    ) {
        fun disconnect() {
            try { socket?.close() } catch (_: IOException) {}
        }
    }
}
