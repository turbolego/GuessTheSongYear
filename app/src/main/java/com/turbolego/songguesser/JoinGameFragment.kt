package com.turbolego.songguesser

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult
import com.turbolego.songguesser.databinding.FragmentJoinGameBinding
import com.turbolego.songguesser.GameSessionManager.GameSession
import com.turbolego.songguesser.GameSessionManager.RevealResult

/**
 * Fragment for joining a network multiplayer game.
 *
 * Enter the host's IP:port manually or scan the host's QR code.
 * On success, navigates to VideoPlayerFragment in client mode.
 */
class JoinGameFragment : Fragment(), GameNetworkListener {

    private var _binding: FragmentJoinGameBinding? = null
    private val binding get() = _binding!!

    private var playerName: String = ""
    private var hostIp: String = ""
    private var hostPort: Int = Protocol.WIFI_SERVER_PORT
    private var hasJoined = false

    companion object {
        private const val TAG = "JoinGameFragment"
        private const val RC_QR_SCAN = 0x0000c0de // arbitrary request code
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
        setupListeners()
        binding.editTextPlayerName.setText("Spiller")
        binding.editTextHostIp.setText("")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (!hasJoined) {
            JoinGameService.stop(requireContext())
        }
        _binding = null
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    private fun setupListeners() {
        binding.buttonConnect.setOnClickListener { connectToHost() }
        binding.buttonScanQr.setOnClickListener { scanQrCode() }
    }

    // ── QR Scan ──────────────────────────────────────────────────────────────

    private fun scanQrCode() {
        val integrator = IntentIntegrator(requireActivity())
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("Skann vertens QR-kode")
        integrator.setCameraId(0) // back camera
        integrator.setBeepEnabled(false)
        integrator.setBarcodeImageEnabled(false)
        integrator.setOrientationLocked(true)
        // Initiate scan — result comes back via onActivityResult
        integrator.initiateScan()
    }

    /**
     * Handle QR scan result. Called by the host Activity when
     * onActivityResult matches our request.
     */
    fun onQrScanResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result: IntentResult? = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null && result.contents != null) {
            val scanned = result.contents.trim()
            // The QR content should be an IP:port string
            // Try to parse and fill in the IP field, then auto-connect
            parseAndConnect(scanned)
        } else {
            Toast.makeText(requireContext(), "Kunne ikke lese QR-kode", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Parse potentially QR-scanned content and connect.
     * Accepts "ip:port", "ip", or "http://ip:port".
     */
    private fun parseAndConnect(content: String) {
        var ipText = content.trim()

        // Strip scheme prefix if present
        if (ipText.startsWith("http://")) ipText = ipText.removePrefix("http://")
        else if (ipText.startsWith("https://")) ipText = ipText.removePrefix("https://")

        // Strip trailing slash
        ipText = ipText.trimEnd('/')

        // Parse IP:port
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

        // Validate IP format
        val ipPattern = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
        if (!ipPattern.matches(ip)) {
            Toast.makeText(requireContext(), "Ugyldig IP i QR-kode: $ip", Toast.LENGTH_SHORT).show()
            return
        }

        // Fill in the IP field and auto-connect
        binding.editTextHostIp.setText(content)
        connectToHost(ip, port)
    }

    // ── Connect ──────────────────────────────────────────────────────────────

    private fun connectToHost() {
        playerName = binding.editTextPlayerName.text.toString().trim()
        if (playerName.isBlank()) {
            Toast.makeText(requireContext(), "Skriv inn navnet ditt", Toast.LENGTH_SHORT).show()
            return
        }

        val ipText = binding.editTextHostIp.text.toString().trim()
        if (ipText.isBlank()) {
            Toast.makeText(requireContext(), "Skriv inn vertens IP eller skann QR", Toast.LENGTH_SHORT).show()
            return
        }

        // Parse IP:port format
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
            Toast.makeText(requireContext(), "Ugyldig IP-adresse", Toast.LENGTH_SHORT).show()
            return
        }

        connectToHost(ip, port)
    }

    /** Internal connect method used by both manual entry and QR scan. */
    private fun connectToHost(ip: String, port: Int) {
        playerName = binding.editTextPlayerName.text.toString().trim()
        if (playerName.isBlank()) {
            Toast.makeText(requireContext(), "Skriv inn navnet ditt", Toast.LENGTH_SHORT).show()
            return
        }

        hostIp = ip
        hostPort = port
        hasJoined = false

        binding.textViewConnectionStatus.visibility = View.VISIBLE
        binding.textViewConnectionStatus.text = "Kobler til $hostIp:$hostPort..."
        binding.progressBarJoin.visibility = View.VISIBLE
        binding.buttonConnect.isEnabled = false
        binding.buttonScanQr.isEnabled = false
        binding.editTextPlayerName.isEnabled = false
        binding.editTextHostIp.isEnabled = false

        JoinGameService.connectWifi(requireContext(), playerName, hostIp, hostPort)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GameNetworkListener
    // ═══════════════════════════════════════════════════════════════════════════

    override fun onHostingStarted(sessionId: String, hostName: String) { /* n/a */ }
    override fun onServiceRegistered(serviceName: String) { /* n/a */ }

    override fun onJoinedSession(session: GameSession) {
        hasJoined = true
        binding.progressBarJoin.visibility = View.GONE
        binding.textViewConnectionStatus.text = "Koblet til ${session.hostName}!"
        Toast.makeText(requireContext(), "Ble med i spillet!", Toast.LENGTH_SHORT).show()

        val allPlayerNames = session.players.keys.toList()

        val activity = requireActivity() as? MainActivity
        if (activity != null) {
            val frag = VideoPlayerFragment.newInstance(
                playerNames = allPlayerNames,
                showReveal = false
            )
            activity.supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, frag)
                .addToBackStack("network_game")
                .commit()
        }
    }

    override fun onPlayerJoined(playerName: String, clientIp: String) { /* n/a */ }
    override fun onPlayerDisconnected(playerName: String) { /* n/a */ }
    override fun onVideoReceived(videoId: String, year: Int, title: String) { /* n/a */ }
    override fun onRevealReceived() { /* n/a */ }
    override fun onRevealResultReceived(results: List<RevealResult>) { /* n/a */ }
    override fun onTurnReceived(playerName: String) { /* n/a */ }
    override fun onGuessReceived(playerName: String, guess: Int, correctYear: Int, score: Int) { /* n/a */ }

    override fun onSessionEnded() {
        hasJoined = false
        binding.textViewConnectionStatus.text = "Spillet ble avsluttet"
    }

    override fun onNetworkError(error: String) {
        requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), "Feil: $error", Toast.LENGTH_LONG).show()
            binding.textViewConnectionStatus.text = "Feil: $error"
            binding.progressBarJoin.visibility = View.GONE
            binding.buttonConnect.isEnabled = true
            binding.buttonScanQr.isEnabled = true
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
