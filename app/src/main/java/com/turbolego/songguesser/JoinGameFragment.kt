package com.turbolego.songguesser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.turbolego.songguesser.databinding.FragmentJoinGameBinding
import com.turbolego.songguesser.GameSessionManager.GameSession
import com.turbolego.songguesser.GameSessionManager.RevealResult

/**
 * Fragment for joining a network multiplayer game via WiFi Direct.
 *
 * Discovers nearby hosts, shows them in a list, and lets the user tap to join.
 * On success, navigates to VideoPlayerFragment in client mode (no reveal button).
 */
class JoinGameFragment : Fragment(), GameNetworkListener {

    private var _binding: FragmentJoinGameBinding? = null
    private val binding get() = _binding!!

    /** Player name entered by the user. */
    private var playerName: String = ""

    /** Discovered hosts. */
    private val discoveredHosts = mutableListOf<DiscoveredHost>()

    /** Adapter for the discovered hosts list. */
    private var hostsAdapter: DiscoveredHostsAdapter? = null

    /** Whether we have joined a session. */
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

        setupRecyclerView()
        setupListeners()

        binding.editTextPlayerName.setText("Spiller")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (!hasJoined) {
            JoinGameService.stop(requireContext())
        }
        _binding = null
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        hostsAdapter = DiscoveredHostsAdapter(discoveredHosts) { host ->
            onHostTapped(host)
        }
        binding.recyclerViewDiscoveredHosts.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
            adapter = hostsAdapter
        }
    }

    private fun setupListeners() {
        binding.editTextPlayerName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            ) {
                startDiscovery()
                true
            } else false
        }
    }

    override fun onResume() {
        super.onResume()
        startDiscovery()
    }

    // ── Discovery ────────────────────────────────────────────────────────────

    private fun startDiscovery() {
        playerName = binding.editTextPlayerName.text.toString().trim()
        if (playerName.isBlank()) {
            Toast.makeText(requireContext(), "Skriv inn navnet ditt", Toast.LENGTH_SHORT).show()
            return
        }

        discoveredHosts.clear()
        hostsAdapter?.notifyDataSetChanged()
        hasJoined = false

        binding.textViewConnectionStatus.visibility = View.VISIBLE
        binding.textViewConnectionStatus.text = "Søker etter spill..."
        binding.progressBarJoin.visibility = View.VISIBLE
        binding.editTextPlayerName.isEnabled = false

        JoinGameService.startDiscovery(requireContext(), playerName)
    }

    private fun onHostTapped(host: DiscoveredHost) {
        if (hasJoined) {
            Toast.makeText(requireContext(), "Allerede med i et spill", Toast.LENGTH_SHORT).show()
            return
        }

        binding.textViewConnectionStatus.text = "Kobler til ${host.displayName}..."
        binding.progressBarJoin.visibility = View.VISIBLE

        if (host.deviceAddress.isNotBlank()) {
            JoinGameService.joinHost(requireContext(), playerName, host.deviceAddress)
        } else {
            Toast.makeText(
                requireContext(),
                "Kobler til ${host.displayName}...",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GameNetworkListener
    // ═══════════════════════════════════════════════════════════════════════════

    override fun onHostingStarted(sessionId: String, hostName: String) {
        // Not applicable for joiner
    }

    override fun onServiceRegistered(serviceName: String) {
        val existing = discoveredHosts.find { it.displayName == serviceName }
        if (existing == null) {
            discoveredHosts.add(DiscoveredHost(serviceName, ""))
            hostsAdapter?.notifyItemInserted(discoveredHosts.size - 1)
            binding.textViewConnectionStatus.text =
                "Fant ${discoveredHosts.size} spill. Trykk for å bli med."
        }
    }

    override fun onJoinedSession(session: GameSession) {
        hasJoined = true
        binding.progressBarJoin.visibility = View.GONE

        Toast.makeText(requireContext(), "Ble med i spillet!", Toast.LENGTH_SHORT).show()
        binding.textViewConnectionStatus.text = "Koblet til ${session.hostName}!"

        val allPlayerNames = session.players.keys.toList()

        // Service stays alive for game sync — navigate to game
        val activity = requireActivity() as? MainActivity
        if (activity != null) {
            val frag = VideoPlayerFragment.newInstance(
                playerNames = allPlayerNames.toList(),
                showReveal = false
            )
            activity.supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, frag)
                .addToBackStack("network_game")
                .commit()
        }
    }

    override fun onPlayerJoined(playerName: String, clientIp: String) {
        // Not used while in the join screen
    }

    override fun onPlayerDisconnected(playerName: String) {
        // Not used while in the join screen
    }

    override fun onVideoReceived(videoId: String, year: Int, title: String) {
        // Not used while in the join screen
    }

    override fun onRevealReceived() {
        // Not used while in the join screen
    }

    override fun onRevealResultReceived(results: List<RevealResult>) {
        // Not used while in the join screen
    }

    override fun onTurnReceived(playerName: String) {
        // Not used while in the join screen
    }

    override fun onGuessReceived(playerName: String, guess: Int, correctYear: Int, score: Int) {
        // Not used while in the join screen
    }

    override fun onSessionEnded() {
        hasJoined = false
        binding.textViewConnectionStatus.text = "Spillet ble avsluttet"
    }

    override fun onNetworkError(error: String) {
        Toast.makeText(requireContext(), "Feil: $error", Toast.LENGTH_LONG).show()
        binding.textViewConnectionStatus.text = "Feil: $error"
        binding.progressBarJoin.visibility = View.GONE
        binding.editTextPlayerName.isEnabled = true
    }

    override fun onHostingStatus(status: String) {
        // Joiner doesn't need hosting status — just log for debugging
    }
}

/**
 * Represents a discovered game host.
 */
data class DiscoveredHost(
    val displayName: String,
    val deviceAddress: String,
)

/**
 * RecyclerView adapter for displaying discovered hosts in JoinGameFragment.
 */
private class DiscoveredHostsAdapter(
    private val hosts: MutableList<DiscoveredHost>,
    private val onTap: (DiscoveredHost) -> Unit,
) : RecyclerView.Adapter<DiscoveredHostsAdapter.VH>() {

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
        holder.text1.text = host.displayName
        holder.text1.setTextColor(
            ResourcesCompat.getColor(holder.itemView.resources, R.color.body_text, null)
        )
        holder.text2.text = "Trykk for å bli med"
        holder.text2.setTextColor(
            ResourcesCompat.getColor(holder.itemView.resources, R.color.muted_text, null)
        )
        holder.itemView.setOnClickListener { onTap(host) }
    }
}