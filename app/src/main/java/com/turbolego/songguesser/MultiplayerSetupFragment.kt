package com.turbolego.songguesser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.turbolego.songguesser.databinding.FragmentMultiplayerSetupBinding

/**
 * Fragment for setting up same-device local multiplayer players.
 */
class MultiplayerSetupFragment : Fragment() {

    private var _binding: FragmentMultiplayerSetupBinding? = null
    private val binding get() = _binding!!
    private var playerAdapter: PlayerSetupAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentMultiplayerSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        MultiPlayerManager.clear()

        playerAdapter = PlayerSetupAdapter(
            players = MultiPlayerManager.allPlayers,
            onRemoveClick = { name ->
                MultiPlayerManager.removePlayer(name)
                refreshPlayerList()
            }
        )
        binding.recyclerViewPlayerList.apply {
            adapter = playerAdapter
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        }

        binding.buttonAddPlayer.setOnClickListener {
            showAddPlayerDialog()
        }

        binding.buttonStartGame.setOnClickListener {
            if (MultiPlayerManager.playerCount < 2) {
                Toast.makeText(requireContext(), R.string.min_players, Toast.LENGTH_SHORT).show()
            } else {
                val players = MultiPlayerManager.allPlayers
                val activity = requireActivity() as? MainActivity
                activity?.startMultiplayerGame(players)
            }
        }

        refreshPlayerList()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showAddPlayerDialog() {
        val input = EditText(requireContext())
        input.hint = getString(R.string.player_name_hint)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.add_player)
            .setView(input)
            .setPositiveButton(R.string.add_player) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(requireContext(), R.string.player_name_required, Toast.LENGTH_SHORT).show()
                } else if (!MultiPlayerManager.addPlayer(name)) {
                    Toast.makeText(requireContext(), "Spilleren finnes allerede", Toast.LENGTH_SHORT).show()
                } else {
                    refreshPlayerList()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshPlayerList() {
        val players = MultiPlayerManager.allPlayers
        playerAdapter?.submitList(players)
        binding.buttonStartGame.isEnabled = players.size >= 2
    }
}