package com.turbolego.songguesser

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
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
import java.util.UUID

/**
 * Service that connects to a hosted game session via TCP (Wi-Fi) or Bluetooth.
 *
 * For Wi-Fi: directly connects via IP:port (no P2P/NSD needed).
 * For Bluetooth: connects via RFCOMM.
 */
class JoinGameService : Service() {

    private val TAG = "JoinGameService"
    private val BT_UUID = UUID.fromString(Protocol.BT_SERVICE_UUID)

    companion object {
        @Volatile var instance: JoinGameService? = null

        private const val EXTRA_PLAYER_NAME = "player_name"
        private const val EXTRA_HOST_IP = "host_ip"
        private const val EXTRA_HOST_PORT = "host_port"
        private const val EXTRA_TRANSPORT = "transport"
        private const val EXTRA_BT_ADDRESS = "bt_address"

        /**
         * Connect to a Wi-Fi host by IP:port.
         */
        fun connectWifi(context: Context, playerName: String, hostIp: String, hostPort: Int = Protocol.WIFI_SERVER_PORT) {
            val intent = Intent(context, JoinGameService::class.java).apply {
                putExtra(EXTRA_PLAYER_NAME, playerName)
                putExtra(EXTRA_HOST_IP, hostIp)
                putExtra(EXTRA_HOST_PORT, hostPort)
                putExtra(EXTRA_TRANSPORT, Protocol.TRANSPORT_WIFI)
                action = ACTION_JOIN_WIFI
            }
            context.startService(intent)
        }

        /**
         * Connect to a Bluetooth host by MAC address.
         */
        fun connectBluetooth(context: Context, playerName: String, btAddress: String) {
            val intent = Intent(context, JoinGameService::class.java).apply {
                putExtra(EXTRA_PLAYER_NAME, playerName)
                putExtra(EXTRA_BT_ADDRESS, btAddress)
                putExtra(EXTRA_TRANSPORT, Protocol.TRANSPORT_BLUETOOTH)
                action = ACTION_JOIN_BLUETOOTH
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JoinGameService::class.java))
        }

        private const val ACTION_JOIN_WIFI = "com.turbolego.songguesser.action.JOIN_WIFI"
        private const val ACTION_JOIN_BLUETOOTH = "com.turbolego.songguesser.action.JOIN_BLUETOOTH"
    }

    // ── State ────────────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socketJob: Job? = null
    private var socket: Socket? = null
    private var btSocket: BluetoothSocket? = null
    private var writer: PrintWriter? = null
    private val writeMutex = Mutex()
    private var playerName: String = ""
    private var transport: String = Protocol.TRANSPORT_WIFI
    private var hostIp: String? = null
    private var hostPort: Int = Protocol.WIFI_SERVER_PORT
    private var btAddress: String? = null
    private var hostName: String? = null
    private var sessionId: String? = null
    private var joined = false

    var networkListener: GameNetworkListener? = null

    /** The local player's pending guess — set by UI before REVEAL. */
    var currentPendingGuess: Int = 2000

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        playerName = intent?.getStringExtra(EXTRA_PLAYER_NAME) ?: "Spiller"
        transport = intent?.getStringExtra(EXTRA_TRANSPORT) ?: Protocol.TRANSPORT_WIFI

        when (intent?.action) {
            ACTION_JOIN_WIFI -> {
                hostIp = intent.getStringExtra(EXTRA_HOST_IP)
                hostPort = intent.getIntExtra(EXTRA_HOST_PORT, Protocol.WIFI_SERVER_PORT)
                if (hostIp != null) {
                    serviceScope.launch { connectTcp(hostIp!!, hostPort) }
                } else {
                    networkListener?.onNetworkError("Ingen vert-IP gitt")
                }
            }
            ACTION_JOIN_BLUETOOTH -> {
                btAddress = intent.getStringExtra(EXTRA_BT_ADDRESS)
                if (btAddress != null) {
                    serviceScope.launch { connectBluetooth(btAddress!!) }
                } else {
                    networkListener?.onNetworkError("Ingen Bluetooth-adresse gitt")
                }
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
                networkListener?.onNetworkError("Ukjent handling")
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

    // ═══════════════════════════════════════════════════════════════════════
    // Wi-Fi: TCP socket connection
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun connectTcp(ip: String, port: Int) {
        Log.d(TAG, "Connecting to $ip:$port")
        networkListener?.onHostingStatus("Kobler til $ip:$port...")

        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(ip, port), Protocol.CONNECT_TIMEOUT_MS)
            socket = sock
            Log.d(TAG, "TCP connected to $ip:$port")

            networkListener?.onHostingStatus("Tilkoblet!")
            handleConnection(
                BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8)),
                PrintWriter(sock.getOutputStream(), true)
            )
        } catch (e: Exception) {
            Log.e(TAG, "TCP connection failed", e)
            networkListener?.onNetworkError("Kunne ikke koble til $ip:$port: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Bluetooth: RFCOMM connection
    // ═══════════════════════════════════════════════════════════════════════

    @Suppress("MissingPermission")
    private suspend fun connectBluetooth(address: String) {
        Log.d(TAG, "Connecting to Bluetooth $address")
        networkListener?.onHostingStatus("Kobler til Bluetooth...")

        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                networkListener?.onNetworkError("Bluetooth støttes ikke")
                return
            }
            val device = adapter.getRemoteDevice(address)
            val btSock = device.createRfcommSocketToServiceRecord(BT_UUID)
            adapter.cancelDiscovery()
            btSock.connect()
            btSocket = btSock
            Log.d(TAG, "Bluetooth connected to ${device.name}")

            networkListener?.onHostingStatus("Bluetooth tilkoblet!")
            handleConnection(
                BufferedReader(InputStreamReader(btSock.inputStream, Charsets.UTF_8)),
                PrintWriter(btSock.outputStream, true)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth connection failed", e)
            networkListener?.onNetworkError("Bluetooth-feil: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Connection lifecycle (shared between TCP and BT)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Handles the protocol after a connection is established.
     * Sends JOIN, waits for JOIN_ACK, then listens for messages.
     */
    private suspend fun handleConnection(reader: BufferedReader, wr: PrintWriter) {
        writer = wr

        // 1) Send JOIN message
        val joinMsg = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_JOIN)
            put(Protocol.FIELD_PLAYER, playerName)
        }.toString()
        wr.println(joinMsg)
        Log.d(TAG, "Sent JOIN as $playerName")

        // 2) Read JOIN_ACK
        try {
            val ackLine = reader.readLine()
            val ack = Protocol.tryParse(ackLine) ?: run {
                networkListener?.onNetworkError("Ugyldig svar fra vert")
                return
            }
            if (ack.optString(Protocol.FIELD_TYPE) != Protocol.MSG_JOIN_ACK) {
                networkListener?.onNetworkError("Uventet svar: ${ack.optString(Protocol.FIELD_TYPE)}")
                return
            }

            sessionId = ack.optString(Protocol.FIELD_SESSION_ID)
            hostName = ack.optString(Protocol.FIELD_HOST_NAME)
            joined = true

            val playersJson = ack.optJSONArray(Protocol.FIELD_PLAYERS) ?: JSONArray()
            val playerNamesList = (0 until playersJson.length()).map { playersJson.optString(it) }
            val players = mutableMapOf<String, GameSessionManager.GameSession.PlayerInfo>()
            for (name in playerNamesList) {
                players[name] = GameSessionManager.GameSession.PlayerInfo(name = name)
            }

            Log.d(TAG, "JOIN_ACK received: session=$sessionId, host=$hostName, players=$playerNamesList")
            networkListener?.onHostingStatus("Koblet til $hostName!")
            networkListener?.onJoinedSession(
                GameSessionManager.GameSession(
                    sessionId = sessionId ?: "",
                    hostName = hostName ?: "Vert",
                    players = players
                )
            )

            // 3) Listen loop for messages from host
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val msg = Protocol.tryParse(line!!) ?: continue
                handleServerMessage(msg)
            }
        } catch (e: IOException) {
            Log.d(TAG, "Connection closed")
        } catch (e: Exception) {
            Log.e(TAG, "Connection error", e)
        } finally {
            if (joined) {
                joined = false
                networkListener?.onSessionEnded()
            }
            try { reader.close() } catch (_: IOException) {}
        }
    }

    private fun handleServerMessage(msg: JSONObject) {
        when (msg.optString(Protocol.FIELD_TYPE)) {
            Protocol.MSG_PLAYER_LIST -> {
                val playersJson = msg.optJSONArray(Protocol.FIELD_PLAYERS) ?: return
                Log.d(TAG, "Player list updated: $playersJson")
            }
            Protocol.MSG_VIDEO -> {
                val videoId = msg.optString(Protocol.FIELD_VIDEO_ID)
                val year = msg.optInt(Protocol.FIELD_YEAR, 0)
                val title = msg.optString(Protocol.FIELD_TITLE, "")
                Log.d(TAG, "VIDEO received: $videoId ($year - $title)")
                networkListener?.onVideoReceived(videoId, year, title)
            }
            Protocol.MSG_REVEAL -> {
                Log.d(TAG, "REVEAL received — sending blind guess: $currentPendingGuess")
                networkListener?.onRevealReceived()
                // Send GUESS_BLIND
                val guessMsg = Protocol.buildJson {
                    put(Protocol.FIELD_TYPE, Protocol.MSG_GUESS_BLIND)
                    put(Protocol.FIELD_PLAYER, playerName)
                    put(Protocol.FIELD_GUESS, currentPendingGuess)
                }.toString()
                serviceScope.launch {
                    writeMutex.withLock {
                        try { writer?.println(guessMsg) } catch (_: Exception) {}
                    }
                }
            }
            Protocol.MSG_REVEAL_RESULT -> {
                val resultsJson = msg.optJSONArray(Protocol.FIELD_RESULTS) ?: return
                val results = (0 until resultsJson.length()).map { i ->
                    val r = resultsJson.getJSONObject(i)
                    GameSessionManager.RevealResult(
                        playerName = r.optString(Protocol.FIELD_PLAYER),
                        guess = r.optInt(Protocol.FIELD_GUESS),
                        correctYear = r.optInt(Protocol.FIELD_CORRECT_YEAR),
                        pointsEarned = r.optInt(Protocol.FIELD_POINTS_EARNED),
                        difference = r.optInt(Protocol.FIELD_DIFFERENCE),
                        isCorrect = r.optBoolean(Protocol.FIELD_IS_CORRECT),
                        totalScore = r.optInt(Protocol.FIELD_TOTAL_SCORE, 0)
                    )
                }
                Log.d(TAG, "REVEAL_RESULT received: $results")
                networkListener?.onRevealResultReceived(results)
            }
            Protocol.MSG_END -> {
                Log.d(TAG, "END received from host")
                networkListener?.onSessionEnded()
                joined = false
                shutdown()
            }
        }
    }

    fun sendGuessBlind(guess: Int) {
        currentPendingGuess = guess
    }

    // ── Shutdown ─────────────────────────────────────────────────────────

    private fun shutdown() {
        Log.d(TAG, "shutdown")
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try { btSocket?.close() } catch (_: Exception) {}
        btSocket = null
        writer = null
        socketJob?.cancel()
        socketJob = null
        networkListener = null
    }
}
