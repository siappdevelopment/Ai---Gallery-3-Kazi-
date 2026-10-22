package com.example.thirdgallery.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.ItemAlbumDetailHeaderBinding

/** One full-width row above the album grid: the album name and its item count. */
class AlbumDetailHeaderAdapter(
    private val title: String,
) : RecyclerView.Adapter<AlbumDetailHeaderAdapter.ViewHolder>() {

    private var count = 0

    fun setCount(count: Int) {
        if (count == this.count) return
        this.count = count
        notifyItemChanged(0)
    }

    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemAlbumDetailHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(title, count)

    class ViewHolder(private val binding: ItemAlbumDetailHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(title: String, count: Int) {
            binding.tvTitle.text = title
            binding.tvCount.text = binding.root.resources.getString(R.string.photo_items, count)
        }
    }
}
