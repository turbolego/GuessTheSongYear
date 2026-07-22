package com.turbolego.songguesser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import com.turbolego.songguesser.databinding.FragmentMultiplayerGameBinding

/**
 * Multiplayer game fragment — handles turn-based guessing for same-device players.
 */
class MultiplayerGameFragment : Fragment() {

    private var _binding: FragmentMultiplayerGameBinding? = null
    private val binding get() = _binding!!

    private var currentDifficulty: Difficulty = Difficulty.MEDIUM

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentMultiplayerGameBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateCurrentPlayerDisplay()
        updateLeaderboard()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun setDifficulty(difficulty: Difficulty) { currentDifficulty = difficulty }

    private fun updateCurrentPlayerDisplay() {
        val name = MultiPlayerManager.getCurrentPlayerName()
        binding.textViewCurrentPlayer.text = getString(R.string.turn_of, name)
    }

    private fun updateLeaderboard() {
        val players = MultiPlayerManager.getLeaderboard()
        val sb = StringBuilder()
        players.forEachIndexed { i, p ->
            when (i) {
                0 -> sb.append("🥇 ")
                1 -> sb.append("🥈 ")
                2 -> sb.append("🥉 ")
                else -> sb.append("$i. ")
            }
            sb.append("${p.name} — ${p.score} poeng\n")
        }
        binding.textViewLeaderboard.text = sb.toString()
    }

    companion object {
        private const val ARG_PLAYERS = "players"

        fun newInstance(players: List<Pair<String, String>>): MultiplayerGameFragment {
            val args = Bundle()
            val names = players.joinToString(",") { it.first }
            args.putString(ARG_PLAYERS, names)
            val frag = MultiplayerGameFragment()
            frag.arguments = args
            return frag
        }
    }
}