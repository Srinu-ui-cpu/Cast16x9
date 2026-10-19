package com.cast16x9.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cast16x9.app.R
import com.cast16x9.app.model.SharedFile

class FileAdapter(
    private val onRemoveClick: (SharedFile) -> Unit
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    private val items = mutableListOf<SharedFile>()

    fun submitList(newItems: List<SharedFile>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = items.size

    inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_file_name)
        private val tvMeta: TextView = itemView.findViewById(R.id.tv_file_meta)
        private val btnRemove: ImageButton = itemView.findViewById(R.id.btn_remove_file)

        fun bind(item: SharedFile) {
            tvName.text = item.name
            val sizeMb = String.format("%.2f MB", item.size / (1024f * 1024f))
            tvMeta.text = "$sizeMb • ${item.mimeType}"

            btnRemove.setOnClickListener { onRemoveClick(item) }
        }
    }
}
