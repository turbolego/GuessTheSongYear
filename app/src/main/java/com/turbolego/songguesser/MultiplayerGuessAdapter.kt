package com.turbolego.songguesser

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.recyclerview.widget.RecyclerView
import com.turbolego.songguesser.databinding.ItemPlayerGuessBinding

/**
 * RecyclerView adapter for multiplayer mode on the same device.
 * Shows each player with their own NumberPicker for guessing the year.
 */
class MultiplayerGuessAdapter(
    private val playerNames: List<String>,
    private val onAllGuessed: () -> Unit
) : RecyclerView.Adapter<MultiplayerGuessAdapter.PlayerViewHolder>() {

    private val guesses = IntArray(playerNames.size) { -1 }
    private val results = arrayOfNulls<String>(playerNames.size)
    private val hasSubmitted = BooleanArray(playerNames.size) { false }
    private var gameOver = false
    private var correctYear: Int = 0

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

    /** Set the correct answer and lock all pickers. */
    fun revealAnswers(year: Int) {
        gameOver = true
        correctYear = year
        notifyDataSetChanged()
    }

    /** Check if all players have submitted. */
    private fun checkAllGuessed() {
        if (hasSubmitted.all { it }) {
            onAllGuessed()
        }
    }

    /** Get each player's guessed year (or -1 if not guessed). */
    fun getAllGuesses(): IntArray = guesses.copyOf()

    inner class PlayerViewHolder(private val binding: ItemPlayerGuessBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(position: Int) {
            val playerName = playerNames[position]
            binding.textViewPlayerName.text = playerName

            val picker = binding.numberPickerYear
            picker.minValue = 1960
            picker.maxValue = 2025
            picker.wrapSelectorWheel = false

            if (guesses[position] != -1) {
                picker.value = guesses[position]
            } else {
                // Start at the middle value (1992) so players scroll up or down
                picker.value = 1992
            }

            // Lock picker after submit or when game is over
            picker.isEnabled = !hasSubmitted[position] && !gameOver

            if (hasSubmitted[position]) {
                binding.buttonSubmitGuess.visibility = View.GONE
                binding.textViewPlayerResult.visibility = View.VISIBLE
                binding.textViewPlayerResult.text = results[position] ?: ""
            } else {
                binding.buttonSubmitGuess.visibility = View.VISIBLE
                binding.textViewPlayerResult.visibility = View.GONE
            }

            binding.buttonSubmitGuess.setOnClickListener {
                val year = picker.value
                guesses[position] = year
                hasSubmitted[position] = true
                notifyItemChanged(position)
                checkAllGuessed()
            }
        }
    }

    fun resetForNewRound() {
        guesses.fill(-1)
        results.fill(null)
        hasSubmitted.fill(false)
        gameOver = false
        correctYear = 0
        notifyDataSetChanged()
    }

    fun setPlayerResult(position: Int, resultText: String) {
        results[position] = resultText
        hasSubmitted[position] = true
        notifyItemChanged(position)
    }
}
