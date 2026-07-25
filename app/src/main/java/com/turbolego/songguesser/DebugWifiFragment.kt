package com.turbolego.songguesser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.turbolego.songguesser.GameSessionManager.GameSession
import com.turbolego.songguesser.GameSessionManager.RevealResult
import com.turbolego.songguesser.databinding.FragmentDebugWifiBinding
import java.net.InetAddress
import java.net.NetworkInterface

private const val TAG = "DebugWifi"

/**
 * WiFi multiplayer diagnostic page.
 * Shows LAN scan results, active host info, connected players, and protocol messages.
 */
class DebugWifiFragment : Fragment(), GameNetworkListener {

    private var _binding: FragmentDebugWifiBinding? = null
    private val binding get() = _binding!!

    private val players = mutableListOf<String>()
    private var playersAdapter: SimplePlayerAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDebugWifiBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        appendLog("═ Debug: WiFi ═")

        // Show device IPs
        showNetworkInfo()

        // Setup RecyclerView
        playersAdapter = SimplePlayerAdapter(players)
        binding.recyclerViewWifiPlayers.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = playersAdapter
        }

        // Buttons
        binding.buttonScanWifi.setOnClickListener {
            scanLan()
        }
        binding.buttonHostTest.setOnClickListener {
            startTestHost()
        }
        binding.buttonStopWifi.setOnClickListener {
            stopServices()
        }
    }

    override fun onDestroyView() {
        HostGameService.pendingListener = null
        _binding = null
        super.onDestroyView()
    }

    private fun showNetworkInfo() {
        appendLog("📡 Nettverk:")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        appendLog("   ${iface.name}: ${addr.hostAddress}")
                    }
                }
            }
        } catch (e: Exception) {
            appendLog("   Feil ved nettverksinfo: ${e.message}")
        }
    }

    private fun scanLan() {
        appendLog("📡 Starter LAN-søk...")
        val playerName = "DebugUser"
        JoinGameService.scanLan(requireContext(), playerName)
        // Register listener for scan results
        JoinGameService.instance?.networkListener = object : GameNetworkListener {
            override fun onHostingStatus(status: String) {
                requireActivity().runOnUiThread {
                    binding.textWifiStatus.text = status
                    appendLog("   $status")
                }
            }
            override fun onServiceRegistered(serviceName: String) {
                appendLog("   Fant vert: $serviceName")
            }
            override fun onNetworkError(error: String) {
                requireActivity().runOnUiThread {
                    binding.textWifiStatus.text = "Feil: $error"
                    appendLog("   ❌ $error")
                }
            }
            // Unused callbacks
            override fun onHostingStarted(sessionId: String, hostName: String) {}
            override fun onJoinedSession(session: GameSession) {}
            override fun onPlayerJoined(playerName: String, clientIp: String) {}
            override fun onPlayerDisconnected(playerName: String) {}
            override fun onVideoReceived(videoId: String, year: Int, title: String) {}
            override fun onRevealReceived() {}
            override fun onRevealResultReceived(results: List<RevealResult>) {}
            override fun onTurnReceived(playerName: String) {}
            override fun onGuessReceived(playerName: String, guess: Int, correctYear: Int, score: Int) {}
            override fun onSessionEnded() {}
        }
    }

    private fun startTestHost() {
        appendLog("🏗 Starter test-vert...")

        HostGameService.pendingListener = object : GameNetworkListener {
            override fun onHostingStarted(sessionId: String, hostName: String) {
                requireActivity().runOnUiThread {
                    binding.textWifiInfo.text = "Session: $sessionId\nVert: $hostName"
                    appendLog("✅ Host startet: $sessionId")
                }
            }
            override fun onHostingStatus(status: String) {
                requireActivity().runOnUiThread {
                    binding.textWifiStatus.text = status
                    if (status.contains(":")) {
                        binding.textWifiInfo.text = status
                    }
                }
            }
            override fun onPlayerJoined(playerName: String, clientIp: String) {
                requireActivity().runOnUiThread {
                    if (!players.contains(playerName)) {
                        players.add(playerName)
                        playersAdapter?.notifyItemInserted(players.size - 1)
                    }
                    appendLog("➕ $playerName ble med (IP: $clientIp)")
                }
            }
            override fun onPlayerDisconnected(playerName: String) {
                requireActivity().runOnUiThread {
                    val idx = players.indexOf(playerName)
                    if (idx >= 0) {
                        players.removeAt(idx)
                        playersAdapter?.notifyItemRemoved(idx)
                    }
                    appendLog("➖ $playerName forlot")
                }
            }
            override fun onServiceRegistered(serviceName: String) {}
            override fun onVideoReceived(videoId: String, year: Int, title: String) {
                appendLog("🎬 Video broadcast: $videoId ($year)")
            }
            override fun onNetworkError(error: String) {
                requireActivity().runOnUiThread {
                    binding.textWifiStatus.text = "Feil: $error"
                    appendLog("❌ $error")
                }
            }
            // Unused
            override fun onJoinedSession(session: GameSession) {}
            override fun onRevealReceived() {}
            override fun onRevealResultReceived(results: List<RevealResult>) {}
            override fun onTurnReceived(playerName: String) {}
            override fun onGuessReceived(playerName: String, guess: Int, correctYear: Int, score: Int) {}
            override fun onSessionEnded() {}
        }

        HostGameService.start(requireContext(), "DebugVert")
    }

    private fun stopServices() {
        appendLog("⏹ Stopper tjenester...")
        try { HostGameService.stop(requireContext()) } catch (_: Exception) {}
        try { JoinGameService.stop(requireContext()) } catch (_: Exception) {}
        players.clear()
        playersAdapter?.notifyDataSetChanged()
        binding.textWifiInfo.text = "Ingen aktiv spilløkt"
        appendLog("✅ Stoppet")
    }

    private fun appendLog(msg: String) {
        DebugLogger.i(TAG, msg)
        requireActivity().runOnUiThread {
            binding.textWifiLog.append(msg + "\n")
            binding.scrollWifiLog.post { binding.scrollWifiLog.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // Unused GameNetworkListener methods (host needs them for interface compliance)
    override fun onHostingStarted(sessionId: String, hostName: String) {}
    override fun onHostingStatus(status: String) {}
    override fun onServiceRegistered(serviceName: String) {}
    override fun onJoinedSession(session: GameSession) {}
    override fun onPlayerJoined(playerName: String, clientIp: String) {}
    override fun onPlayerDisconnected(playerName: String) {}
    override fun onVideoReceived(videoId: String, year: Int, title: String) {}
    override fun onRevealReceived() {}
    override fun onRevealResultReceived(results: List<RevealResult>) {}
    override fun onTurnReceived(playerName: String) {}
    override fun onGuessReceived(playerName: String, guess: Int, correctYear: Int, score: Int) {}
    override fun onSessionEnded() {}
    override fun onNetworkError(error: String) {}
}

/**
 * Simple RecyclerView adapter for listing player names.
 */
private class SimplePlayerAdapter(
    private val players: List<String>
) : androidx.recyclerview.widget.RecyclerView.Adapter<SimplePlayerAdapter.VH>() {

    class VH(itemView: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
        val text: TextView = itemView.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = players.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.text.text = players[position]
        holder.text.setTextColor(
            androidx.core.content.res.ResourcesCompat.getColor(
                holder.itemView.resources, R.color.body_text, null
            )
        )
    }
}
