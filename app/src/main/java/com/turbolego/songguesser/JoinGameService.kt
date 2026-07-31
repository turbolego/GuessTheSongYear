package com.turbolego.songguesser

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import java.util.concurrent.ConcurrentLinkedQueue
import javax.net.ssl.SSLSocket

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

        /**
         * Pending listener — set by the fragment BEFORE calling scan/connect.
         * Transferred to [networkListener] in onCreate() (same pattern as HostGameService).
         */
        @Volatile var pendingListener: GameNetworkListener? = null

        /**
         * Pending callback — receives [LanHost] objects as they are discovered
         * during LAN or Bluetooth scans. The fragment sets this before calling
         * [scanLan] or [scanBluetooth] to populate its host list in real time.
         */
        @Volatile var pendingHostCallback: ((LanHost) -> Unit)? = null

        private const val EXTRA_PLAYER_NAME = "player_name"
        private const val EXTRA_HOST_IP = "host_ip"
        private const val EXTRA_HOST_PORT = "host_port"
        private const val EXTRA_TRANSPORT = "transport"
        private const val EXTRA_BT_ADDRESS = "bt_address"
        private const val EXTRA_TLS_SPKI_HASH = "tls_spki_hash"

        /**
         * Scan the LAN for any active hosts. Returns via networkListener callbacks.
         * Probes 192.168.x.2–254 by sending HELLO and expecting ACK.
         */
        fun scanLan(context: Context, playerName: String) {
            val intent = Intent(context, JoinGameService::class.java).apply {
                putExtra(EXTRA_PLAYER_NAME, playerName)
                putExtra(EXTRA_TRANSPORT, Protocol.TRANSPORT_WIFI)
                action = ACTION_SCAN_LAN
            }
            context.startService(intent)
        }

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
         * Connect to a Wi-Fi host via TLS with SPKI pinning.
         * Uses the host's TLS port (8889) and verifies the certificate
         * against [tlsSpkiHash] obtained during LAN discovery.
         */
        fun connectWifiTls(context: Context, playerName: String, hostIp: String, tlsSpkiHash: String) {
            val intent = Intent(context, JoinGameService::class.java).apply {
                putExtra(EXTRA_PLAYER_NAME, playerName)
                putExtra(EXTRA_HOST_IP, hostIp)
                putExtra(EXTRA_HOST_PORT, Protocol.WIFI_TLS_SERVER_PORT)
                putExtra(EXTRA_TLS_SPKI_HASH, tlsSpkiHash)
                putExtra(EXTRA_TRANSPORT, Protocol.TRANSPORT_WIFI)
                action = ACTION_JOIN_WIFI
            }
            context.startService(intent)
        }

        /**
         * Scan nearby Bluetooth devices for active game hosts.
         * Probes bonded and discovered devices via RFCOMM HELLO/ACK.
         */
        fun scanBluetooth(context: Context, playerName: String) {
            val intent = Intent(context, JoinGameService::class.java).apply {
                putExtra(EXTRA_PLAYER_NAME, playerName)
                putExtra(EXTRA_TRANSPORT, Protocol.TRANSPORT_BLUETOOTH)
                action = ACTION_SCAN_BLUETOOTH
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
        private const val ACTION_SCAN_LAN = "com.turbolego.songguesser.action.SCAN_LAN"
        private const val ACTION_SCAN_BLUETOOTH = "com.turbolego.songguesser.action.SCAN_BLUETOOTH"
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
    private var tlsSpkiHash: String? = null
    private var auth: Protocol.MessageAuthenticator? = null

    var networkListener: GameNetworkListener? = null

    /** The local player's pending guess — set by UI before REVEAL. */
    var currentPendingGuess: Int = 2000

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        pendingListener?.let {
            networkListener = it
            pendingListener = null
        }
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
                tlsSpkiHash = intent.getStringExtra(EXTRA_TLS_SPKI_HASH)
                if (hostIp != null) {
                    val useTls = tlsSpkiHash != null
                    serviceScope.launch { connectTcp(hostIp!!, hostPort, useTls = useTls) }
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
            ACTION_SCAN_LAN -> {
                serviceScope.launch { scanLanForHosts() }
            }
            ACTION_SCAN_BLUETOOTH -> {
                serviceScope.launch { scanBluetoothForHosts() }
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

    // ═══════════════════════════════════════════════════════════════════════════
    // LAN SCANNING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Scan all 192.168.x.x subnets that our device might be on.
     * Probe each host by sending HELLO; listen for ACK with hostName.
     * Uses parallel coroutines for speed (~3s for all 254 IPs per subnet).
     */
    private suspend fun scanLanForHosts() {
        networkListener?.onHostingStatus("Søker etter spill på LAN...")

        // Get the WiFi IP to determine which subnet(s) to scan
        val wifiManager = getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val wifiInfo = wifiManager?.connectionInfo
        val myIp = (wifiInfo?.ipAddress ?: 0).let {
            formatIp(it)
        }

        Log.d(TAG, "LAN scan: my IP = $myIp")

        // Determine subnets to scan
        val subnets = mutableSetOf<String>()

        // Extract subnet from our own IP
        val mySubnet = myIp.let {
            val lastDot = it.lastIndexOf('.')
            if (lastDot > 0) it.substring(0, lastDot) else ""
        }
        if (mySubnet.isNotBlank() && mySubnet.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}$"""))) {
            subnets.add(mySubnet)
        }

        // Always scan 192.168.1.x (most common) and 192.168.0.x (secondary)
        subnets.add("192.168.1")
        subnets.add("192.168.0")

        Log.d(TAG, "Scanning subnets: $subnets")

        val discoveredHosts = mutableListOf<LanHost>()
        val scannedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val totalHosts = subnets.sumOf { 253 } // .2–254 per subnet

        // CoroutineScope for parallel probes — bound to serviceScope
        coroutineScope {
            for (subnet in subnets) {
                // Launch 128 probes per subnet in parallel, then the rest
                val ips = (2..254).map { "$subnet.$it" }.toList()
                ips.forEach { ip ->
                    launch {
                        probeHost(ip, Protocol.WIFI_SERVER_PORT)?.let { host ->
                            synchronized(discoveredHosts) {
                                discoveredHosts.add(host)
                            }
                            networkListener?.onServiceRegistered(host.hostName)
                            pendingHostCallback?.invoke(host)
                            Log.d(TAG, "Found host: ${host.hostName} at $ip")
                        }
                        val done = scannedCount.incrementAndGet()
                        if (done >= totalHosts) {
                            // All done — report final status
                            networkListener?.onHostingStatus(
                                if (discoveredHosts.isEmpty()) {
                                    "Fant ingen spill. Skjekk at verten er aktiv."
                                } else {
                                    "Fant ${discoveredHosts.size} vert(er). Trykk for å bli med."
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Probe a single IP:port for a host. Send HELLO, read ACK.
     * Returns a LanHost on success, null on timeout or failure.
     */
    private suspend fun probeHost(ip: String, port: Int): LanHost? = withContext(Dispatchers.IO) {
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), 800) // 800ms timeout
            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

            // Send HELLO
            val hello = Protocol.buildJson {
                put(Protocol.FIELD_TYPE, Protocol.MSG_HELLO)
            }
            writer.println(hello.toString())

            // Read response (must be ACK with hostName)
            val line = reader.readLine() ?: return@withContext null
            val msg = Protocol.tryParse(line) ?: return@withContext null
            if (msg.optString(Protocol.FIELD_TYPE) != Protocol.MSG_ACK) return@withContext null

            val hostName = msg.optString(Protocol.FIELD_HOST_NAME, "Vert")
            val playersJson = msg.optJSONArray(Protocol.FIELD_PLAYERS)
            val playerCount = playersJson?.length() ?: 0

            // Probe TLS port for SPKI hash (fire-and-forget — don't fail if unavailable)
            var tlsSpkiHash: String? = null
            try {
                val tlsSocket = Socket()
                tlsSocket.connect(InetSocketAddress(ip, Protocol.WIFI_TLS_SERVER_PORT), 600)
                val tlsWriter = PrintWriter(tlsSocket.getOutputStream(), true)
                val tlsReader = BufferedReader(InputStreamReader(tlsSocket.getInputStream(), Charsets.UTF_8))

                val tlsHello = Protocol.buildJson {
                    put(Protocol.FIELD_TYPE, Protocol.MSG_TLS_HELLO)
                }
                tlsWriter.println(tlsHello.toString())

                val tlsLine = tlsReader.readLine()
                val tlsMsg = tlsLine?.let { Protocol.tryParse(it) }
                if (tlsMsg != null && tlsMsg.optString(Protocol.FIELD_TYPE) == Protocol.MSG_ACK) {
                    tlsSpkiHash = tlsMsg.optString(Protocol.FIELD_TLS_SPKI_HASH, null)
                    if (tlsSpkiHash.isNullOrBlank()) tlsSpkiHash = null
                }

                try { tlsSocket.close() } catch (_: Exception) {}
            } catch (_: Exception) {
                // TLS probe is non-essential — silently ignore
            }

            LanHost(hostName, ip, port, playerCount, tlsSpkiHash)
        } catch (_: Exception) {
            null
        } finally {
            @Suppress("TooGenericExceptionCaught")
            try { /* socket closed by try-with-resources via local val */ } catch (_: Exception) {}
        }
    }

    /** Format integer IP (network byte order) to dotted string. */
    private fun formatIp(ipInt: Int): String {
        return String.format(
            "%d.%d.%d.%d",
            ipInt and 0xFF,
            (ipInt shr 8) and 0xFF,
            (ipInt shr 16) and 0xFF,
            (ipInt shr 24) and 0xFF
        )
    }

    /**
     * Data class representing a host discovered via LAN scan.
     */
    data class LanHost(
        val hostName: String,
        val ip: String,
        val port: Int,
        val playerCount: Int,
        val tlsSpkiHash: String? = null,
        /** MAC address when discovered via Bluetooth; null for Wi-Fi. */
        val btAddress: String? = null
    )

    // ═══════════════════════════════════════════════════════════════════════
    // Wi-Fi: TCP socket connection
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun connectTcp(ip: String, port: Int, useTls: Boolean = false) {
        Log.d(TAG, "Connecting to $ip:$port (useTls=$useTls)")
        networkListener?.onHostingStatus("Kobler til $ip:$port...")

        try {
            val (sock, reader, wr) = if (useTls) {
                // Attempt TLS first, fall back to plain TCP
                try {
                    val tlsSock = if (tlsSpkiHash != null) {
                        Log.d(TAG, "Attempting SPKI-pinned TLS to $ip:${Protocol.WIFI_TLS_SERVER_PORT}")
                        SecureChannelManager.createPinnedClientSSLSocket(ip, Protocol.WIFI_TLS_SERVER_PORT, tlsSpkiHash!!)
                    } else {
                        Log.d(TAG, "Attempting relaxed TLS to $ip:${Protocol.WIFI_TLS_SERVER_PORT}")
                        SecureChannelManager.createRelaxedClientSSLSocket(ip, Protocol.WIFI_TLS_SERVER_PORT)
                    }
                    Log.d(TAG, "TLS connected to $ip:${Protocol.WIFI_TLS_SERVER_PORT}")
                    val tlsReader = BufferedReader(InputStreamReader(tlsSock.getInputStream(), Charsets.UTF_8))
                    val tlsWriter = PrintWriter(tlsSock.getOutputStream(), true)
                    Triple(tlsSock as java.net.Socket, tlsReader, tlsWriter)
                } catch (e: Exception) {
                    Log.w(TAG, "TLS connection failed, falling back to plain TCP: ${e.message}")
                    val plainSock = Socket()
                    plainSock.connect(InetSocketAddress(ip, port), Protocol.CONNECT_TIMEOUT_MS)
                    Log.d(TAG, "TCP connected to $ip:$port (fallback)")
                    val plainReader = BufferedReader(InputStreamReader(plainSock.getInputStream(), Charsets.UTF_8))
                    val plainWriter = PrintWriter(plainSock.getOutputStream(), true)
                    Triple(plainSock, plainReader, plainWriter)
                }
            } else {
                val sock = Socket()
                sock.connect(InetSocketAddress(ip, port), Protocol.CONNECT_TIMEOUT_MS)
                Log.d(TAG, "TCP connected to $ip:$port")
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(sock.getOutputStream(), true)
                Triple(sock, reader, writer)
            }

            socket = sock
            networkListener?.onHostingStatus("Tilkoblet!")
            handleConnection(reader, wr)
        } catch (e: Exception) {
            Log.e(TAG, "TCP connection failed", e)
            networkListener?.onNetworkError("Kunne ikke koble til $ip:$port: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Bluetooth: RFCOMM connection & scanning
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
            // Connect on a dedicated thread so withTimeout can cancel the coroutine
            withTimeout(Protocol.CONNECT_TIMEOUT_MS.toLong()) {
                withContext(Dispatchers.IO) { btSock.connect() }
            }
            btSocket = btSock
            Log.d(TAG, "Bluetooth connected to ${device.name}")

            networkListener?.onHostingStatus("Bluetooth tilkoblet!")
            handleConnection(
                BufferedReader(InputStreamReader(btSock.inputStream, Charsets.UTF_8)),
                PrintWriter(btSock.outputStream, true)
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Bluetooth connection timed out", e)
            networkListener?.onNetworkError("Bluetooth-timeout: tilkobling tok for lang tid")
        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth connection failed", e)
            networkListener?.onNetworkError("Bluetooth-feil: ${e.message}")
        }
    }

    /**
     * Scan for Bluetooth devices running GuessTheSongYear hosts.
     *
     * Probes bonded devices first, then starts device discovery for 12 seconds.
     * Each candidate device is probed via RFCOMM: send HELLO, expect ACK with host info.
     * Discovered hosts are reported via [networkListener] callbacks.
     */
    @Suppress("MissingPermission")
    private suspend fun scanBluetoothForHosts() {
        Log.d(TAG, "scanBluetoothForHosts")
        networkListener?.onHostingStatus("Søker etter Bluetooth-verter...")

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            networkListener?.onHostingStatus("Bluetooth støttes ikke")
            return
        }
        if (!adapter.isEnabled) {
            networkListener?.onHostingStatus("Bluetooth er ikke slått på")
            return
        }

        val foundHosts = mutableListOf<LanHost>()
        val probedAddresses = mutableSetOf<String>()

        // 1) Probe already-bonded devices (quick, no discovery needed)
        val bonded = adapter.bondedDevices?.toList() ?: emptyList()
        for (device in bonded) {
            if (!serviceScope.isActive) break
            val host = probeBtDevice(device)
            if (host != null && probedAddresses.add(device.address)) {
                foundHosts.add(host)
                networkListener?.onServiceRegistered(host.hostName)
                pendingHostCallback?.invoke(host)
                Log.d(TAG, "Found BT host (bonded): ${host.hostName} @ ${device.address}")
            }
        }

        // 2) Start discovery for non-bonded devices (only if nothing found)
        if (foundHosts.isEmpty()) {
            networkListener?.onHostingStatus("Søker etter Bluetooth-verter... (oppdager enheter)")

            val discoveredDevices = mutableListOf<BluetoothDevice>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            @Suppress("DEPRECATION")
                            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
                            if (device != null && bonded.none { it.address == device.address }) {
                                discoveredDevices.add(device)
                            }
                        }
                    }
                }
            }

            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }

            try {
                registerReceiver(receiver, filter)
                adapter.startDiscovery()

                // Allow collection for 12 seconds
                delay(12_000)

                adapter.cancelDiscovery()

                // Probe the newly discovered devices
                for (device in discoveredDevices) {
                    if (!serviceScope.isActive) break
                    if (probedAddresses.contains(device.address)) continue
                    val host = probeBtDevice(device)
                    if (host != null && probedAddresses.add(device.address)) {
                        foundHosts.add(host)
                        networkListener?.onServiceRegistered(host.hostName)
                        pendingHostCallback?.invoke(host)
                        Log.d(TAG, "Found BT host (discovered): ${host.hostName} @ ${device.address}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "BT discovery error", e)
            } finally {
                try { unregisterReceiver(receiver) } catch (_: Exception) {}
            }
        }

        // 3) Report final status
        if (foundHosts.isEmpty()) {
            networkListener?.onHostingStatus("Fant ingen Bluetooth-verter")
        } else {
            networkListener?.onHostingStatus("Fant ${foundHosts.size} vert(er) via Bluetooth")
        }
    }

    /**
     * Probe a single Bluetooth device to see if it is hosting a GuessTheSongYear game.
     *
     * Connects via RFCOMM, sends HELLO, expects ACK with host info.
     * Returns a [LanHost] on success, null on timeout or failure.
     */
    @Suppress("MissingPermission")
    private suspend fun probeBtDevice(device: BluetoothDevice): LanHost? =
        withContext(Dispatchers.IO) {
            val sock = try {
                device.createRfcommSocketToServiceRecord(BT_UUID)
            } catch (e: Exception) {
                return@withContext null
            }
            try {
                withTimeout(5000) { sock.connect() }

                val reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
                val writer = PrintWriter(sock.outputStream, true)

                val hello = Protocol.buildJson {
                    put(Protocol.FIELD_TYPE, Protocol.MSG_HELLO)
                }
                writer.println(hello.toString())

                val line = reader.readLine()
                val msg = line?.let { Protocol.tryParse(it) }
                if (msg?.optString(Protocol.FIELD_TYPE) == Protocol.MSG_ACK) {
                    val hostName = msg.optString(Protocol.FIELD_HOST_NAME, "Vert")
                    val playersJson = msg.optJSONArray(Protocol.FIELD_PLAYERS)
                    val playerCount = playersJson?.length() ?: 0
                    LanHost(hostName, device.address, 0, playerCount, btAddress = device.address)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            } finally {
                try { sock.close() } catch (_: Exception) {}
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

            // Extract session key for message signing
            val sessionKeyStr = ack.optString(Protocol.FIELD_SESSION_KEY, null)
            if (sessionKeyStr != null && sessionKeyStr.isNotEmpty()) {
                auth = Protocol.createAuthenticator(sessionKeyStr)
                Log.d(TAG, "Session key received, message signing active")
            } else {
                Log.w(TAG, "No session key in JOIN_ACK — messages will be unsigned")
            }

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
        // Verify HMAC signature from host
        if (auth != null && !auth!!.verify(msg)) {
            Log.w(TAG, "Dropped message from host: invalid HMAC signature")
            return
        }
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
                // Send GUESS_BLIND with HMAC signature
                val guessMsg = Protocol.buildJson {
                    put(Protocol.FIELD_TYPE, Protocol.MSG_GUESS_BLIND)
                    put(Protocol.FIELD_PLAYER, playerName)
                    put(Protocol.FIELD_GUESS, currentPendingGuess)
                }
                auth?.sign(guessMsg)
                val payload = guessMsg.toString()
                serviceScope.launch {
                    writeMutex.withLock {
                        try { writer?.println(payload) } catch (_: Exception) {}
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
