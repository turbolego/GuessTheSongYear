package com.turbolego.songguesser

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.recyclerview.widget.RecyclerView
import com.turbolego.songguesser.databinding.ItemPlayerGuessBinding

/**
 * RecyclerView adapter for multiplayer mode on the same device.
 * Shows each player with their own NumberPicker — all players
 * guess simultaneously. The host clicks "Vis svar" to lock in
 * every player's current picker value.
 */
class MultiplayerGuessAdapter(
    private val playerNames: List<String>
) : RecyclerView.Adapter<MultiplayerGuessAdapter.PlayerViewHolder>() {

    /** Mirrors each player's current picker value, updated on every bind. */
    private val currentValues = IntArray(playerNames.size) { 1992 }
    private val results = arrayOfNulls<String>(playerNames.size)
    private var gameOver = false

    override fun getItemCount(): Int = playerNames.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayerViewHolder {
        val binding = ItemPlayerGuessBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PlayerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlayerViewHolder, position: Int) {
        holder.bind(position)
    }

    /** Read every player's current picker value. */
    fun getCurrentPickerValues(): List<Pair<String, Int>> {
        return playerNames.indices.map { i ->
            playerNames[i] to currentValues[i]
        }
    }

    /** Lock all pickers and mark the game round over. */
    fun revealAnswers() {
        gameOver = true
        notifyDataSetChanged()
    }

    /** Show a result string below a specific player's picker. */
    fun setPlayerResult(position: Int, resultText: String) {
        results[position] = resultText
        notifyItemChanged(position)
    }

    /** Reset all results and unlock pickers for a new round. */
    fun resetForNewRound() {
        results.fill(null)
        gameOver = false
        notifyDataSetChanged()
    }

    inner class PlayerViewHolder(private val binding: ItemPlayerGuessBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(position: Int) {
            val playerName = playerNames[position]
            binding.textViewPlayerName.text = playerName

            val picker = binding.numberPickerYear
            picker.minValue = 1960
            picker.maxValue = 2025
            picker.wrapSelectorWheel = false

            // Restore previous value or set default
            picker.value = currentValues[position]

            // Update our mirror array whenever the picker changes
            picker.setOnValueChangedListener { _, _, newVal ->
                currentValues[position] = newVal
            }

            // Lock after Vis svar
            picker.isEnabled = !gameOver

            // Show or hide result
            val resultText = results[position]
            if (resultText != null) {
                binding.textViewPlayerResult.visibility = View.VISIBLE
                binding.textViewPlayerResult.text = resultText
            } else {
                binding.textViewPlayerResult.visibility = View.GONE
            }
        }
    }
}
