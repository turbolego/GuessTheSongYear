package com.turbolego.songguesser

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.turbolego.songguesser.databinding.FragmentHostGameBinding
import com.turbolego.songguesser.GameSessionManager.GameSession
import com.turbolego.songguesser.GameSessionManager.RevealResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragment for hosting a network multiplayer game.
 *
 * Starts [HostGameService] which opens a plain TCP server socket.
 * Shows the host's IP:port and a QR code that joiners can scan.
 */
class HostGameFragment : Fragment(), GameNetworkListener {

    private var _binding: FragmentHostGameBinding? = null
    private val binding get() = _binding!!

    private var hostName: String = ""
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
            Toast.makeText(requireContext(), R.string.host_enter_name, Toast.LENGTH_SHORT).show()
            return
        }

        joinedPlayers.clear()
        playerAdapter?.notifyDataSetChanged()
        isHosting = true

        binding.textViewHostStatus.visibility = View.VISIBLE
        binding.textViewHostStatus.text = getString(R.string.host_starting_server)
        binding.progressBarHost.visibility = View.VISIBLE

        HostGameService.pendingListener = this
        HostGameService.start(requireContext(), hostName)

        binding.buttonHostWifi.isEnabled = false
        binding.buttonHostBluetooth.isEnabled = false
        binding.editTextHostName.isEnabled = false
        binding.textViewTransportHint.text = getString(R.string.host_starting_hint)
    }

    private fun startHostingViaBluetooth() {
        hostName = binding.editTextHostName.text.toString().trim()
        if (hostName.isBlank()) {
            Toast.makeText(requireContext(), R.string.host_enter_name, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.BLUETOOTH_ADVERTISE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE),
                    REQUEST_BLUETOOTH_ADVERTISE
                )
                return
            }
        }

        joinedPlayers.clear()
        playerAdapter?.notifyDataSetChanged()
        isHosting = true

        binding.textViewHostStatus.visibility = View.VISIBLE
        binding.textViewHostStatus.text = getString(R.string.host_starting_bt)
        binding.progressBarHost.visibility = View.VISIBLE

        HostGameService.pendingListener = this
        HostGameService.start(requireContext(), hostName, Protocol.TRANSPORT_BLUETOOTH)

        binding.buttonHostWifi.isEnabled = false
        binding.buttonHostBluetooth.isEnabled = false
        binding.editTextHostName.isEnabled = false
        binding.textViewTransportHint.text = getString(R.string.host_starting_bt_hint)
    }

    private fun startGame() {
        if (sessionId == null) {
            Toast.makeText(requireContext(), R.string.host_wait_for_start, Toast.LENGTH_SHORT).show()
            return
        }
        val allPlayers = mutableListOf(hostName)
        allPlayers.addAll(joinedPlayers)
        if (allPlayers.size < 2) {
            Toast.makeText(requireContext(), R.string.host_min_players, Toast.LENGTH_SHORT).show()
            return
        }

        isHosting = false
        val frag = VideoPlayerFragment.newInstance(
            playerNames = allPlayers
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
        Toast.makeText(requireContext(), getString(R.string.host_copied, ip), Toast.LENGTH_SHORT).show()
    }

    /** Generate a QR code bitmap from text using ZXing. Runs on background thread. */
    private fun showQrCode(content: String) {
        lifecycleScope.launch {
            val qrBitmap = withContext(Dispatchers.IO) {
                try {
                    val writer = MultiFormatWriter()
                    val bitMatrix: BitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512)
                    val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
                    for (x in 0 until 512) {
                        for (y in 0 until 512) {
                            bmp.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                        }
                    }
                    bmp
                } catch (e: Exception) {
                    Log.e(TAG, "QR code generation failed", e)
                    null
                }
            }
            qrBitmap?.let {
                binding.imageViewQrCode.setImageBitmap(it)
                binding.textViewQrLabel.visibility = View.VISIBLE
                binding.imageViewQrCode.visibility = View.VISIBLE
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GameNetworkListener
    // ═══════════════════════════════════════════════════════════════════════════

    override fun onHostingStarted(sessionId: String, hostName: String) {
        this.sessionId = sessionId
        this.hostName = hostName
        requireActivity().runOnUiThread {
            if (_binding == null) return@runOnUiThread
            binding.textViewHostStatus.text = getString(R.string.host_active)
            binding.textViewTransportHint.text = getString(R.string.host_item, hostName)
            binding.progressBarHost.visibility = View.GONE
            binding.textViewPlayersLabel.visibility = View.VISIBLE
            binding.recyclerViewJoinedPlayers.visibility = View.VISIBLE
        }
    }

    override fun onHostingStatus(status: String) {
        requireActivity().runOnUiThread {
            binding.textViewHostStatus.text = status
            // If the status contains an IP like "192.168.1.42:8888", show it big + QR
            val ipPattern = Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d+""")
            val match = ipPattern.find(status)
            if (match != null) {
                hostIp = match.value
                binding.textViewHostIp.text = hostIp
                binding.textViewHostIp.visibility = View.VISIBLE
                binding.buttonCopyIp.visibility = View.VISIBLE
                binding.textViewTransportHint.text = getString(R.string.host_share_ip)
                // Generate QR code
                showQrCode(match.value)
            }
        }
    }

    override fun onServiceRegistered(serviceName: String) {
        requireActivity().runOnUiThread {
            if (_binding == null) return@runOnUiThread
            binding.textViewHostStatus.text = getString(R.string.host_visible_as, serviceName)
        }
    }

    override fun onJoinedSession(session: GameSession) { /* n/a for host */ }

    override fun onPlayerJoined(playerName: String, clientIp: String) {
        requireActivity().runOnUiThread {
            if (_binding == null) return@runOnUiThread
            if (!joinedPlayers.contains(playerName)) {
                joinedPlayers.add(playerName)
                playerAdapter?.notifyItemInserted(joinedPlayers.size - 1)
                if (joinedPlayers.size >= 1) {
                    binding.buttonStartGame.visibility = View.VISIBLE
                    binding.buttonStartGame.isEnabled = true
                    binding.textViewHostStatus.text =
                        getString(R.string.host_players_joined, joinedPlayers.size)
                } else {
                    binding.textViewHostStatus.text = getString(R.string.host_waiting)
                }
            }
        }
    }

    override fun onPlayerDisconnected(playerName: String) {
        requireActivity().runOnUiThread {
            if (_binding == null) return@runOnUiThread
            val index = joinedPlayers.indexOf(playerName)
            if (index >= 0) {
                joinedPlayers.removeAt(index)
                playerAdapter?.notifyItemRemoved(index)
                if (joinedPlayers.isEmpty()) {
                    binding.buttonStartGame.visibility = View.GONE
                    binding.textViewHostStatus.text = getString(R.string.host_waiting)
                } else {
                    binding.textViewHostStatus.text =
                        getString(R.string.host_players_joined, joinedPlayers.size)
                }
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
            Toast.makeText(requireContext(), getString(R.string.host_error, error), Toast.LENGTH_LONG).show()
            binding.textViewHostStatus.text = getString(R.string.host_error, error)
            binding.progressBarHost.visibility = View.GONE
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        when (requestCode) {
            REQUEST_BLUETOOTH_ADVERTISE -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    startHostingViaBluetooth()
                } else {
                    Toast.makeText(requireContext(), R.string.host_bt_permission, Toast.LENGTH_LONG).show()
                }
            }
            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    companion object {
        private const val TAG = "HostGameFragment"
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