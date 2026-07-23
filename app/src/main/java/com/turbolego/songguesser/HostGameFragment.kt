package com.turbolego.songguesser

import android.Manifest
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
 * Fragment for hosting a network multiplayer game via WiFi Direct or Bluetooth.
 *
 * Lets the user pick a transport, starts HostGameService, shows joined players,
 * and provides a "Start spill" button that navigates to VideoPlayerFragment.
 */
class HostGameFragment : Fragment(), GameNetworkListener {

    private var _binding: FragmentHostGameBinding? = null
    private val binding get() = _binding!!

    /** Name the host entered for themselves. */
    private var hostName: String = "Vert"

    /** Players that have joined (not including host). */
    private val joinedPlayers = mutableListOf<String>()

    /** Adapter for the RecyclerView showing joined players. */
    private var playerAdapter: JoinedPlayerAdapter? = null

    /** Whether hosting has been started. */
    private var isHosting = false

    /** Session ID once hosting starts. */
    private var sessionId: String? = null

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
        requestBluetoothPermission()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (isHosting) {
            HostGameService.stop(requireContext())
        }
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
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.BLUETOOTH_ADVERTISE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    requireActivity(),
                    arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE),
                    REQUEST_BLUETOOTH_ADVERTISE
                )
            }
        }
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
        binding.textViewHostStatus.text = getString(R.string.hosting_status_wifi_start)
        binding.progressBarHost.visibility = View.VISIBLE

        // Register listener BEFORE starting service — uses race-free pendingListener
        HostGameService.pendingListener = this

        // Start the service — listener will be picked up in onCreate()
        HostGameService.start(requireContext(), hostName)

        binding.buttonHostWifi.isEnabled = false
        binding.buttonHostBluetooth.isEnabled = false
        binding.editTextHostName.isEnabled = false
        binding.textViewTransportHint.text = "WiFi-vertskap aktivt. Del koden med vennene dine."
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
                Toast.makeText(
                    requireContext(),
                    "Bluetooth-tillatelse kreves for Bluetooth-hosting",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        Toast.makeText(
            requireContext(),
            "Bluetooth-hosting kommer snart! Bruk WiFi.",
            Toast.LENGTH_LONG
        ).show()
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

        // Navigate to game — service stays alive for sync
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

    // ═══════════════════════════════════════════════════════════════════════════
    // GameNetworkListener
    // ═══════════════════════════════════════════════════════════════════════════

    override fun onHostingStarted(sessionId: String, hostName: String) {
        this.sessionId = sessionId
        this.hostName = hostName
        binding.textViewHostStatus.text = "✅ Vertskap aktivt!"
        binding.textViewTransportHint.text = "Host: $hostName | Spill: $sessionId"
        binding.progressBarHost.visibility = View.GONE
        binding.textViewPlayersLabel.visibility = View.VISIBLE
        binding.recyclerViewJoinedPlayers.visibility = View.VISIBLE
    }

    override fun onHostingStatus(status: String) {
        requireActivity().runOnUiThread {
            binding.textViewHostStatus.text = status
        }
    }

    override fun onServiceRegistered(serviceName: String) {
        binding.textViewHostStatus.text = "Spillet er synlig som «$serviceName»"
    }

    override fun onJoinedSession(session: GameSession) {
        // Not applicable for host
    }

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

    override fun onVideoReceived(videoId: String, year: Int, title: String) {
        // Not used in host setup fragment
    }

    override fun onRevealReceived() {
        // Not used in host setup fragment
    }

    override fun onRevealResultReceived(results: List<RevealResult>) {
        // Not used in host setup fragment
    }

    override fun onTurnReceived(playerName: String) {
        // Not used in host setup fragment
    }

    override fun onGuessReceived(playerName: String, guess: Int, correctYear: Int, score: Int) {
        // Not used in host setup fragment
    }

    override fun onSessionEnded() {
        isHosting = false
    }

    override fun onNetworkError(error: String) {
        Toast.makeText(requireContext(), "Nettverksfeil: $error", Toast.LENGTH_LONG).show()
        binding.textViewHostStatus.text = "Feil: $error"
        binding.progressBarHost.visibility = View.GONE
    }

    companion object {
        private const val REQUEST_BLUETOOTH_ADVERTISE = 1001
    }
}

/**
 * RecyclerView adapter for displaying joined player names in the HostGameFragment.
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