package com.turbolego.songguesser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult
import com.turbolego.songguesser.databinding.FragmentJoinGameBinding
import com.turbolego.songguesser.GameSessionManager.GameSession
import com.turbolego.songguesser.GameSessionManager.RevealResult

/**
 * Fragment for joining a network multiplayer game.
 *
 * On entry: automatically scans the LAN for active hosts using TCP HELLO probes.
 * Discovered hosts show in a tap-to-join list.
 * QR code scanning and manual IP entry are fallbacks.
 */
class JoinGameFragment : Fragment(), GameNetworkListener {

    private var _binding: FragmentJoinGameBinding? = null
    private val binding get() = _binding!!

    private var playerName: String = ""
    private var hostIp: String = ""
    private var hostPort: Int = Protocol.WIFI_SERVER_PORT
    private var hasJoined = false

    /** Hosts discovered via LAN or Bluetooth scan. */
    private val discoveredHosts = mutableListOf<JoinGameService.LanHost>()
    private var hostsAdapter: LanHostsAdapter? = null

    companion object {
        private const val TAG = "JoinGameFragment"
        private const val RC_QR_SCAN = 0x0000c0de
        private const val REQUEST_BLUETOOTH_SCAN = 2001
        private const val REQUEST_BLUETOOTH_CONNECT = 2002
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentJoinGameBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupListeners()
        binding.editTextPlayerName.setText(getString(R.string.join_player_default))
        binding.editTextHostIp.setText("")
    }

    override fun onResume() {
        super.onResume()
        startLanScan()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clearCallbacks()
        if (!hasJoined) {
            JoinGameService.stop(requireContext())
        }
        _binding = null
    }

    /** Clear pending callbacks to prevent leaks. */
    private fun clearCallbacks() {
        if (JoinGameService.pendingListener === this) {
            JoinGameService.pendingListener = null
        }
        JoinGameService.pendingHostCallback = null
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        hostsAdapter = LanHostsAdapter(discoveredHosts) { host ->
            if (host.btAddress != null) {
                connectToBluetoothHost(host.btAddress, host.hostName)
            } else {
                connectToHost(host.ip, host.port)
            }
        }
        binding.recyclerViewDiscoveredHosts.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = hostsAdapter
        }
    }

    private fun setupListeners() {
        binding.buttonConnect.setOnClickListener { connectToHost() }
        binding.buttonScanQr.setOnClickListener { scanQrCode() }
        binding.buttonRefreshScan.setOnClickListener { startLanScan() }
        binding.buttonScanBluetooth.setOnClickListener { startBluetoothScan() }
    }

    // ── LAN Scan ─────────────────────────────────────────────────────────────

    private fun startLanScan() {
        playerName = binding.editTextPlayerName.text.toString().trim()
        if (playerName.isBlank()) {
            Toast.makeText(requireContext(), R.string.join_enter_name, Toast.LENGTH_SHORT).show()
            return
        }

        discoveredHosts.clear()
        hostsAdapter?.notifyDataSetChanged()
        hasJoined = false

        binding.textViewConnectionStatus.visibility = View.VISIBLE
        binding.textViewConnectionStatus.text = getString(R.string.join_scanning_lan)
        binding.progressBarJoin.visibility = View.VISIBLE
        binding.buttonRefreshScan.isEnabled = false
        binding.buttonScanQr.isEnabled = false

        JoinGameService.pendingListener = this
        JoinGameService.pendingHostCallback = { host ->
            requireActivity().runOnUiThread {
                if (_binding == null) return@runOnUiThread
                discoveredHosts.add(host)
                hostsAdapter?.notifyItemInserted(discoveredHosts.size - 1)
            }
        }
        JoinGameService.scanLan(requireContext(), playerName)
    }

    // ── Bluetooth Scan ────────────────────────────────────────────────────────

    private fun startBluetoothScan() {
        playerName = binding.editTextPlayerName.text.toString().trim()
        if (playerName.isBlank()) {
            Toast.makeText(requireContext(), R.string.join_enter_name, Toast.LENGTH_SHORT).show()
            return
        }

        // Check runtime permissions on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
                    REQUEST_BLUETOOTH_SCAN
                )
                return
            }
        }

        discoveredHosts.clear()
        hostsAdapter?.notifyDataSetChanged()
        hasJoined = false

        binding.textViewConnectionStatus.visibility = View.VISIBLE
        binding.textViewConnectionStatus.text = getString(R.string.join_scanning_bt)
        binding.progressBarJoin.visibility = View.VISIBLE
        binding.buttonScanBluetooth.isEnabled = false

        JoinGameService.pendingListener = this
        JoinGameService.pendingHostCallback = { host ->
            requireActivity().runOnUiThread {
                if (_binding == null) return@runOnUiThread
                discoveredHosts.add(host)
                hostsAdapter?.notifyItemInserted(discoveredHosts.size - 1)
            }
        }
        JoinGameService.scanBluetooth(requireContext(), playerName)
    }

    /** Connect to a Bluetooth host by MAC address. */
    private fun connectToBluetoothHost(btAddress: String, hostNameHint: String) {
        playerName = binding.editTextPlayerName.text.toString().trim()
        if (playerName.isBlank()) {
            Toast.makeText(requireContext(), R.string.join_enter_name, Toast.LENGTH_SHORT).show()
            return
        }

        // Check BLUETOOTH_CONNECT permission (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    REQUEST_BLUETOOTH_CONNECT
                )
                return
            }
        }

        hasJoined = false

        binding.textViewConnectionStatus.visibility = View.VISIBLE
        binding.textViewConnectionStatus.text = getString(R.string.join_connecting, hostNameHint)
        binding.progressBarJoin.visibility = View.VISIBLE
        disableAllButtons()

        JoinGameService.pendingListener = this
        JoinGameService.connectBluetooth(requireContext(), playerName, btAddress)
    }

    private fun scanQrCode() {
        val integrator = IntentIntegrator(requireActivity())
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt(getString(R.string.join_scan_qr_prompt))
        integrator.setCameraId(0)
        integrator.setBeepEnabled(false)
        integrator.setBarcodeImageEnabled(false)
        integrator.setOrientationLocked(true)
        integrator.initiateScan()
    }

    fun onQrScanResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result: IntentResult? = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null && result.contents != null) {
            val scanned = result.contents.trim()
            parseAndConnect(scanned)
        } else {
            Toast.makeText(requireContext(), R.string.join_qr_read_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseAndConnect(content: String) {
        var ipText = content.trim()
        if (ipText.startsWith("http://")) ipText = ipText.removePrefix("http://")
        else if (ipText.startsWith("https://")) ipText = ipText.removePrefix("https://")
        ipText = ipText.trimEnd('/')

        val colonIndex = ipText.lastIndexOf(':')
        val ip: String
        val port: Int
        if (colonIndex > 0) {
            ip = ipText.substring(0, colonIndex).trim()
            port = ipText.substring(colonIndex + 1).trim().toIntOrNull() ?: Protocol.WIFI_SERVER_PORT
        } else {
            ip = ipText.trim()
            port = Protocol.WIFI_SERVER_PORT
        }

        val ipPattern = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
        if (!ipPattern.matches(ip)) {
            Toast.makeText(requireContext(), getString(R.string.join_invalid_qr_ip, ip), Toast.LENGTH_SHORT).show()
            return
        }

        binding.editTextHostIp.setText(content)
        connectToHost(ip, port)
    }

    // ── Connect ──────────────────────────────────────────────────────────────

    private fun connectToHost() {
        playerName = binding.editTextPlayerName.text.toString().trim()
        if (playerName.isBlank()) {
            Toast.makeText(requireContext(), R.string.join_enter_name, Toast.LENGTH_SHORT).show()
            return
        }

        val ipText = binding.editTextHostIp.text.toString().trim()
        if (ipText.isBlank()) {
            Toast.makeText(requireContext(), R.string.join_enter_ip_hint, Toast.LENGTH_SHORT).show()
            return
        }

        val colonIndex = ipText.lastIndexOf(':')
        val ip: String
        val port: Int
        if (colonIndex > 0) {
            ip = ipText.substring(0, colonIndex).trim()
            port = ipText.substring(colonIndex + 1).trim().toIntOrNull() ?: Protocol.WIFI_SERVER_PORT
        } else {
            ip = ipText.trim()
            port = Protocol.WIFI_SERVER_PORT
        }

        val ipPattern = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
        if (!ipPattern.matches(ip)) {
            Toast.makeText(requireContext(), R.string.join_invalid_ip, Toast.LENGTH_SHORT).show()
            return
        }

        connectToHost(ip, port)
    }

    /** Internal connect used by LAN tap, manual entry, and QR scan. */
    private fun connectToHost(ip: String, port: Int) {
        playerName = binding.editTextPlayerName.text.toString().trim()
        if (playerName.isBlank()) {
            Toast.makeText(requireContext(), R.string.join_enter_name, Toast.LENGTH_SHORT).show()
            return
        }

        hostIp = ip
        hostPort = port
        hasJoined = false

        // Fill IP field so user sees what they're connecting to
        binding.editTextHostIp.setText("$hostIp:$hostPort")

        binding.textViewConnectionStatus.visibility = View.VISIBLE
        binding.textViewConnectionStatus.text = getString(R.string.join_connecting, "$hostIp:$hostPort")
        binding.progressBarJoin.visibility = View.VISIBLE
        binding.buttonConnect.isEnabled = false
        binding.buttonScanQr.isEnabled = false
        binding.buttonRefreshScan.isEnabled = false
        binding.buttonScanBluetooth.isEnabled = false
        binding.editTextPlayerName.isEnabled = false
        binding.editTextHostIp.isEnabled = false

        JoinGameService.pendingListener = this
        JoinGameService.pendingHostCallback = null // clear scan callback
        JoinGameService.connectWifi(requireContext(), playerName, hostIp, hostPort)
    }

    /** Disable all interactive controls in the join UI. */
    private fun disableAllButtons() {
        binding.buttonConnect.isEnabled = false
        binding.buttonScanQr.isEnabled = false
        binding.buttonRefreshScan.isEnabled = false
        binding.buttonScanBluetooth.isEnabled = false
        binding.editTextPlayerName.isEnabled = false
        binding.editTextHostIp.isEnabled = false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        when (requestCode) {
            REQUEST_BLUETOOTH_SCAN -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    startBluetoothScan()
                } else {
                    Toast.makeText(requireContext(), R.string.host_bt_permission, Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_BLUETOOTH_CONNECT -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    // Re-trigger after permission granted — need the MAC address again
                    Toast.makeText(requireContext(), R.string.join_scan_bt_button, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), R.string.host_bt_permission, Toast.LENGTH_LONG).show()
                }
            }
            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GameNetworkListener
    // ═══════════════════════════════════════════════════════════════════════════

    override fun onHostingStarted(sessionId: String, hostName: String) { /* n/a */ }

    override fun onServiceRegistered(serviceName: String) {
        // Hosts are added to the discovered list via pendingHostCallback
    }

    override fun onJoinedSession(session: GameSession) {
        hasJoined = true
        requireActivity().runOnUiThread {
            if (_binding == null) return@runOnUiThread
            binding.progressBarJoin.visibility = View.GONE
            binding.textViewConnectionStatus.text = getString(R.string.join_connected, session.hostName)
            Toast.makeText(requireContext(), R.string.join_joined, Toast.LENGTH_SHORT).show()

            val allPlayerNames = session.players.keys.toList()

            val activity = requireActivity() as? MainActivity
            if (activity != null) {
                val frag = VideoPlayerFragment.newInstance(
                    playerNames = allPlayerNames
                )
                // Mark as network client — won't auto-load videos, waits for host
                frag.setAsNetworkClient()
                activity.supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, frag)
                    .addToBackStack("network_game")
                    .commit()
            }
        }
    }

    override fun onPlayerJoined(playerName: String, clientIp: String) { /* n/a */ }
    override fun onPlayerDisconnected(playerName: String) { /* n/a */ }
    override fun onVideoReceived(videoId: String, year: Int, title: String) {
        // Forward to the active VideoPlayerFragment (setAsNetworkClient was called on create)
        VideoPlayerFragment.activeFragment?.receiveVideoFromHost(videoId, year)
        VideoPlayerFragment.activeFragment?.setAsNetworkClient()
    }
    override fun onRevealReceived() {
        // Forward reveal to the active VideoPlayerFragment
        // The host has locked all guesses — clients show the reveal UI
        requireActivity().runOnUiThread {
            VideoPlayerFragment.activeFragment?.let { frag ->
                if (frag.isAdded && !frag.isDetached) {
                    // Show the reveal button so answers can be shown
                    frag.revealMultiplayerAnswers()
                }
            }
        }
    }
    override fun onRevealResultReceived(results: List<RevealResult>) {
        // Forward results to the active VideoPlayerFragment for display
        requireActivity().runOnUiThread {
            VideoPlayerFragment.activeFragment?.let { frag ->
                if (frag.isAdded && !frag.isDetached) {
                    // Update each player's result text from the server's calculation
                    for (result in results) {
                        val index = frag.playerNames.indexOf(result.playerName)
                        if (index >= 0) {
                            frag.multiplayerAdapter?.setPlayerResult(
                                index,
                                result.playerName + ": " +
                                    getString(R.string.score_earned, result.pointsEarned)
                            )
                        }
                    }
                    frag.multiplayerAdapter?.revealAnswers()
                    frag.revealMultiplayerAnswers()
                }
            }
        }
    }
    override fun onTurnReceived(playerName: String) { /* n/a */ }
    override fun onGuessReceived(playerName: String, guess: Int, correctYear: Int, score: Int) { /* n/a */ }

    override fun onSessionEnded() {
        hasJoined = false
        binding.textViewConnectionStatus.text = getString(R.string.join_game_ended)
    }

    override fun onNetworkError(error: String) {
        requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), getString(R.string.join_error, error), Toast.LENGTH_LONG).show()
            binding.textViewConnectionStatus.text = getString(R.string.join_error, error)
            binding.progressBarJoin.visibility = View.GONE
            binding.buttonConnect.isEnabled = true
            binding.buttonScanQr.isEnabled = true
            binding.buttonRefreshScan.isEnabled = true
            binding.buttonScanBluetooth.isEnabled = true
            binding.editTextPlayerName.isEnabled = true
            binding.editTextHostIp.isEnabled = true
        }
    }

    override fun onHostingStatus(status: String) {
        requireActivity().runOnUiThread {
            binding.textViewConnectionStatus.text = status
        }
    }
}

/**
 * RecyclerView adapter for displaying discovered LAN hosts.
 */
private class LanHostsAdapter(
    private val hosts: MutableList<JoinGameService.LanHost>,
    private val onTap: (JoinGameService.LanHost) -> Unit,
) : RecyclerView.Adapter<LanHostsAdapter.VH>() {

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text1: TextView = itemView.findViewById(android.R.id.text1)
        val text2: TextView = itemView.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = hosts.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val host = hosts[position]
        val context = holder.itemView.context
        holder.text1.text = host.hostName
        holder.text1.setTextColor(
            ResourcesCompat.getColor(holder.itemView.resources, R.color.body_text, null)
        )
        holder.text2.text = if (host.btAddress != null) {
            "${context.getString(R.string.join_player_count, host.playerCount)} · ${host.ip}"
        } else {
            context.getString(
                R.string.join_player_count,
                host.playerCount
            ) + " · ${host.ip}:${host.port}"
        }
        holder.text2.setTextColor(
            ResourcesCompat.getColor(holder.itemView.resources, R.color.muted_text, null)
        )
        holder.itemView.setOnClickListener { onTap(host) }
    }
}