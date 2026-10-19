package com.cast16x9.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cast16x9.app.R
import com.cast16x9.app.model.MediaItem
import com.cast16x9.app.model.MediaType

class MediaAdapter(
    private val onItemClick: (MediaItem) -> Unit
) : RecyclerView.Adapter<MediaAdapter.MediaViewHolder>() {

    private val items = mutableListOf<MediaItem>()

    fun submitList(newItems: List<MediaItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false)
        return MediaViewHolder(view)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = items.size

    inner class MediaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_media_title)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tv_media_subtitle)
        private val tvTypeBadge: TextView = itemView.findViewById(R.id.tv_type_badge)

        fun bind(item: MediaItem) {
            tvTitle.text = item.title
            val sizeMb = String.format("%.1f MB", item.size / (1024f * 1024f))
            tvSubtitle.text = if (item.type == MediaType.VIDEO && item.duration > 0) {
                val durSec = (item.duration / 1000) % 60
                val durMin = (item.duration / (1000 * 60))
                String.format("%02d:%02d • %s", durMin, durSec, sizeMb)
            } else {
                sizeMb
            }

            tvTypeBadge.text = if (item.type == MediaType.VIDEO) "🎬 VIDEO" else "🖼️ PHOTO"

            itemView.setOnClickListener { onItemClick(item) }
        }
    }
}
