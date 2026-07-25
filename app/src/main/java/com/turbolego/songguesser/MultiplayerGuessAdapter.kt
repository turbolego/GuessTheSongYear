package com.turbolego.songguesser

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.turbolego.songguesser.databinding.ItemPlayerGuessBinding

/**
 * RecyclerView adapter for multiplayer mode on the same device.
 * Shows each player with their own year EditText — all players
 * guess simultaneously. The host clicks "Vis svar" to lock in
 * every player's current text value.
 */
class MultiplayerGuessAdapter(
    private val playerNames: List<String>
) : RecyclerView.Adapter<MultiplayerGuessAdapter.PlayerViewHolder>() {

    /** Mirrors each player's current edit text value, updated on every change. */
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

    /** Read every player's current edit text value. */
    fun getCurrentPickerValues(): List<Pair<String, Int>> {
        return playerNames.indices.map { i ->
            playerNames[i] to currentValues[i]
        }
    }

    /** Lock all inputs and mark the game round over. */
    fun revealAnswers() {
        gameOver = true
        notifyDataSetChanged()
    }

    /** Show a result string below a specific player's input. */
    fun setPlayerResult(position: Int, resultText: String) {
        results[position] = resultText
        notifyItemChanged(position)
    }

    /** Reset all results and unlock inputs for a new round. */
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

            val editYear = binding.editTextYear

            // Set current value
            val currentVal = currentValues[position]
            editYear.setText(if (currentVal > 0) currentVal.toString() else "")

            // Lock after Vis svar
            editYear.isEnabled = !gameOver

            // Update mirror when text changes
            editYear.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString() ?: ""
                    val year = text.toIntOrNull()
                    if (year != null && year in 1960..2025) {
                        currentValues[position] = year
                    } else if (text.isEmpty()) {
                        currentValues[position] = 0
                    }
                }
            })

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