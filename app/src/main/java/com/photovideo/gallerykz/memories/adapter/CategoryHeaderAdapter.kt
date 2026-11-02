package com.photovideo.gallerykz.memories.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.databinding.ItemAlbumDetailHeaderBinding

/** First row of a "Made for you" category: its name and "N items · what it holds" (design: Best photos). */
class CategoryHeaderAdapter(
    private val title: String,
    private val subtitle: String,
) : RecyclerView.Adapter<CategoryHeaderAdapter.ViewHolder>() {

    private var count = 0

    fun setCount(count: Int) {
        if (count == this.count) return
        this.count = count
        notifyItemChanged(0)
    }

    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemAlbumDetailHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(title, subtitle, count)

    class ViewHolder(private val binding: ItemAlbumDetailHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(title: String, subtitle: String, count: Int) {
            binding.tvTitle.text = title
            binding.tvCount.text = binding.root.resources.getString(R.string.category_meta, count, subtitle)
        }
    }
}
