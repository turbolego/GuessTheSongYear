package com.turbolego.songguesser

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.turbolego.songguesser.databinding.FragmentHostGameBinding
import com.turbolego.songguesser.GameSessionManager.GameSession
import com.turbolego.songguesser.GameSessionManager.RevealResult

/**
 * Fragment for hosting a network multiplayer game.
 *
 * Starts [HostGameService] which opens a plain TCP server socket.
 * The host's IP:port is shown in the UI — joiners enter it manually.
 */
class HostGameFragment : Fragment(), GameNetworkListener {

    private var _binding: FragmentHostGameBinding? = null
    private val binding get() = _binding!!

    private var hostName: String = "Vert"
    private val joinedPlayers = mutableListOf<String>()
    private var playerAdapter: JoinedPlayerAdapter? = null
    private var isHosting = false
    private var sessionId: String? = null
    private var hostIp: String? = null

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHostGameBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupListeners()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (isHosting) HostGameService.stop(requireContext())
        _binding = null
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        playerAdapter = JoinedPlayerAdapter(joinedPlayers)
        binding.recyclerViewJoinedPlayers.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = playerAdapter
        }
    }

    private fun setupListeners() {
        binding.buttonHostWifi.setOnClickListener { startHostingViaWifi() }
        binding.buttonHostBluetooth.setOnClickListener { startHostingViaBluetooth() }
        binding.buttonStartGame.setOnClickListener { startGame() }
        binding.buttonCopyIp.setOnClickListener { copyIp() }
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private fun startHostingViaWifi() {
        hostName = binding.editTextHostName.text.toString().trim()
        if (hostName.isBlank()) {
            Toast.makeText(requireContext(), "Skriv inn navnet ditt", Toast.LENGTH_SHORT).show()
            return
        }

        joinedPlayers.clear()
        playerAdapter?.notifyDataSetChanged()
        isHosting = true

        binding.textViewHostStatus.visibility = View.VISIBLE
        binding.textViewHostStatus.text = "Starter server..."
        binding.progressBarHost.visibility = View.VISIBLE

        // Set listener before start — service picks it up via pendingListener
        HostGameService.pendingListener = this
        HostGameService.start(requireContext(), hostName)

        binding.buttonHostWifi.isEnabled = false
        binding.buttonHostBluetooth.isEnabled = false
        binding.editTextHostName.isEnabled = false
        binding.textViewTransportHint.text = "Vertskap starter..."
    }

    private fun startHostingViaBluetooth() {
        hostName = binding.editTextHostName.text.toString().trim()
        if (hostName.isBlank()) {
            Toast.makeText(requireContext(), "Skriv inn navnet ditt", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.BLUETOOTH_ADVERTISE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(requireContext(), "Bluetooth-tillatelse kreves", Toast.LENGTH_LONG).show()
                return
            }
        }

        joinedPlayers.clear()
        playerAdapter?.notifyDataSetChanged()
        isHosting = true

        binding.textViewHostStatus.visibility = View.VISIBLE
        binding.textViewHostStatus.text = "Starter Bluetooth..."
        binding.progressBarHost.visibility = View.VISIBLE

        HostGameService.pendingListener = this
        HostGameService.start(requireContext(), hostName, Protocol.TRANSPORT_BLUETOOTH)

        binding.buttonHostWifi.isEnabled = false
        binding.buttonHostBluetooth.isEnabled = false
        binding.editTextHostName.isEnabled = false
        binding.textViewTransportHint.text = "Bluetooth-vertskap starter..."
    }

    private fun startGame() {
        if (sessionId == null) {
            Toast.makeText(requireContext(), "Vent på at vertskap starter", Toast.LENGTH_SHORT).show()
            return
        }
        val allPlayers = mutableListOf(hostName)
        allPlayers.addAll(joinedPlayers)
        if (allPlayers.size < 2) {
            Toast.makeText(requireContext(), "Trenger minst 2 spillere totalt", Toast.LENGTH_SHORT).show()
            return
        }

        isHosting = false
        val frag = VideoPlayerFragment.newInstance(
            playerNames = allPlayers,
            showReveal = true
        )
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .addToBackStack("network_game")
            .commitAllowingStateLoss()
    }

    private fun copyIp() {
        val ip = hostIp ?: return
        val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Host IP", ip))
        Toast.makeText(requireContext(), "Kopiert: $ip", Toast.LENGTH_SHORT).show()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GameNetworkListener
    // ═══════════════════════════════════════════════════════════════════════════

    override fun onHostingStarted(sessionId: String, hostName: String) {
        this.sessionId = sessionId
        this.hostName = hostName
        binding.textViewHostStatus.text = "Vertskap aktivt"
        binding.textViewTransportHint.text = "Host: $hostName"
        binding.progressBarHost.visibility = View.GONE
        binding.textViewPlayersLabel.visibility = View.VISIBLE
        binding.recyclerViewJoinedPlayers.visibility = View.VISIBLE
    }

    override fun onHostingStatus(status: String) {
        requireActivity().runOnUiThread {
            binding.textViewHostStatus.text = status
            // If the status contains an IP like "192.168.1.42:8888", show it big
            val ipPattern = Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d+""")
            val match = ipPattern.find(status)
            if (match != null) {
                hostIp = match.value
                binding.textViewHostIp.text = hostIp
                binding.textViewHostIp.visibility = View.VISIBLE
                binding.buttonCopyIp.visibility = View.VISIBLE
                binding.textViewTransportHint.text = "Del denne IP-adressen med andre spillere"
            }
        }
    }

    override fun onServiceRegistered(serviceName: String) {
        binding.textViewHostStatus.text = "Synlig som «$serviceName»"
    }

    override fun onJoinedSession(session: GameSession) { /* n/a for host */ }

    override fun onPlayerJoined(playerName: String, clientIp: String) {
        if (!joinedPlayers.contains(playerName)) {
            joinedPlayers.add(playerName)
            playerAdapter?.notifyItemInserted(joinedPlayers.size - 1)
            if (joinedPlayers.size >= 1) {
                binding.buttonStartGame.visibility = View.VISIBLE
                binding.buttonStartGame.isEnabled = true
                binding.textViewHostStatus.text = "${joinedPlayers.size} spiller(e) har blitt med"
            } else {
                binding.textViewHostStatus.text = "Venter på spillere..."
            }
        }
    }

    override fun onPlayerDisconnected(playerName: String) {
        val index = joinedPlayers.indexOf(playerName)
        if (index >= 0) {
            joinedPlayers.removeAt(index)
            playerAdapter?.notifyItemRemoved(index)
            if (joinedPlayers.isEmpty()) {
                binding.buttonStartGame.visibility = View.GONE
                binding.textViewHostStatus.text = "Venter på spillere..."
            } else {
                binding.textViewHostStatus.text = "${joinedPlayers.size} spiller(e) har blitt med"
            }
        }
    }

    override fun onVideoReceived(videoId: String, year: Int, title: String) { /* n/a */ }
    override fun onRevealReceived() { /* n/a */ }
    override fun onRevealResultReceived(results: List<RevealResult>) { /* n/a */ }
    override fun onTurnReceived(playerName: String) { /* n/a */ }
    override fun onGuessReceived(playerName: String, guess: Int, correctYear: Int, score: Int) { /* n/a */ }
    override fun onSessionEnded() { isHosting = false }

    override fun onNetworkError(error: String) {
        requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), "Feil: $error", Toast.LENGTH_LONG).show()
            binding.textViewHostStatus.text = "Feil: $error"
            binding.progressBarHost.visibility = View.GONE
        }
    }

    companion object {
        private const val REQUEST_BLUETOOTH_ADVERTISE = 1001
    }
}

/**
 * RecyclerView adapter for displaying joined player names.
 */
private class JoinedPlayerAdapter(
    private val players: MutableList<String>,
) : RecyclerView.Adapter<JoinedPlayerAdapter.VH>() {

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameText: TextView = itemView.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = players.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.nameText.text = players[position]
        holder.nameText.setTextColor(
            ResourcesCompat.getColor(
                holder.itemView.resources, R.color.body_text, null
            )
        )
    }
}
