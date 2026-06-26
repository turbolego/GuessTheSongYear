package com.turbolego.songguesser

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class YouTubeAdapter(
    private val onItemClick: (String) -> Unit
) : ListAdapter<YouTubeVideo, YouTubeAdapter.YoutubeViewHolder>(YouTubeDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): YoutubeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_youtube, parent, false)
        return YoutubeViewHolder(view)
    }

    override fun onBindViewHolder(holder: YoutubeViewHolder, position: Int) {
        holder.bind(getItem(position), holder.itemView.context!!)
    }

    inner class YoutubeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.videoThumbnail)
        private val title: TextView = itemView.findViewById(R.id.videoTitle)
        private val channel: TextView = itemView.findViewById(R.id.videoChannel)
        private val views: TextView = itemView.findViewById(R.id.videoViews)

        fun bind(video: YouTubeVideo, context: Context) {
            title.text = video.title
            channel.text = video.channelTitle
            views.text = "Unknown date"
            Glide.with(context)
                .load(video.thumbnailUrl)
                .placeholder(R.drawable.ic_placeholder)
                .into(thumbnail)
            itemView.setOnClickListener {
                onItemClick(video.id)
            }
        }
    }

    class YouTubeDiffCallback : DiffUtil.ItemCallback<YouTubeVideo>() {
        override fun areItemsTheSame(oldItem: YouTubeVideo, newItem: YouTubeVideo): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: YouTubeVideo, newItem: YouTubeVideo): Boolean {
            return oldItem == newItem
        }
    }
}
