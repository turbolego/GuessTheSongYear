package com.turbolego.songguesser

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.turbolego.songguesser.databinding.ItemPlayerRowBinding

class PlayerSetupAdapter(
    private var players: List<MultiPlayerManager.Player>,
    private val onRemoveClick: (String) -> Unit,
) : RecyclerView.Adapter<PlayerSetupAdapter.ViewHolder>() {

    override fun getItemCount(): Int = players.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPlayerRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(players[position])
    }

    fun submitList(newPlayers: List<MultiPlayerManager.Player>) {
        players = newPlayers
        notifyDataSetChanged()
    }

    inner class ViewHolder(private val binding: ItemPlayerRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(player: MultiPlayerManager.Player) {
            binding.textViewPlayerName.text = "${bindingAdapterPosition + 1}. ${player.name}"
            binding.textViewPlayerScore.visibility = android.view.View.GONE
            binding.buttonRemovePlayer.setOnClickListener {
                onRemoveClick(player.name)
            }
        }
    }
}