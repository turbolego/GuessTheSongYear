package com.turbolego.songguesser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.turbolego.songguesser.databinding.FragmentPlayerStatsBinding

class PlayerStatsFragment : Fragment() {

    private var _binding: FragmentPlayerStatsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPlayerStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.buttonClearStats.setOnClickListener { confirmClear() }
        render()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun render() {
        val summaries = PlayerStatistics.summaries(requireContext())
        binding.textStatsContent.text = if (summaries.isEmpty()) {
            getString(R.string.player_stats_empty)
        } else {
            summaries.joinToString("\n\n") { summary ->
                getString(
                    R.string.player_stats_item,
                    summary.playerName,
                    summary.guesses,
                    summary.exactGuesses,
                    summary.points,
                )
            }
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.player_stats_clear)
            .setMessage(R.string.reset_score_message)
            .setPositiveButton(R.string.reset) { _, _ ->
                PlayerStatistics.clear(requireContext())
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
