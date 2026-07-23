package com.turbolego.songguesser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import com.turbolego.songguesser.databinding.FragmentJoinGameBinding
import com.turbolego.songguesser.GameSessionManager.GameSession
import com.turbolego.songguesser.GameSessionManager.RevealResult

/**
 * Fragment for joining a network multiplayer game.
 *
 * Enter the host's IP:port and player name, then tap "Koble til".
 * On success, navigates to VideoPlayerFragment in client mode.
 */
class JoinGameFragment : Fragment(), GameNetworkListener {

    private var _binding: FragmentJoinGameBinding? = null
    private val binding get() = _binding!!

    private var playerName: String = ""
    private var hostIp: String = ""
    private var hostPort: Int = Protocol.WIFI_SERVER_PORT
    private var hasJoined = false

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
            Toast.makeText(requireContext(), "Skriv inn vertens IP", Toast.LENGTH_SHORT).show()
            return
        }

        // Parse IP:port format (e.g. "192.168.1.42:8888")
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

        // Basic IP validation
        val ipPattern = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
        if (!ipPattern.matches(ip)) {
            Toast.makeText(requireContext(), "Ugyldig IP-adresse", Toast.LENGTH_SHORT).show()
            return
        }

        hostIp = ip
        hostPort = port
        hasJoined = false

        binding.textViewConnectionStatus.visibility = View.VISIBLE
        binding.textViewConnectionStatus.text = "Kobler til $hostIp:$hostPort..."
        binding.progressBarJoin.visibility = View.VISIBLE
        binding.buttonConnect.isEnabled = false
        binding.editTextPlayerName.isEnabled = false
        binding.editTextHostIp.isEnabled = false

        JoinGameService.connectWifi(requireContext(), playerName, hostIp, hostPort)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GameNetworkListener
    // ═══════════════════════════════════════════════════════════════════════════

    override fun onHostingStarted(sessionId: String, hostName: String) {
        // Not applicable for joiner
    }

    override fun onServiceRegistered(serviceName: String) {
        // Not applicable for joiner
    }

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
