package com.example.thirdgallery.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.ItemSearchHeaderBinding

/** Group title of the results ("Photos & videos") with the number of matches on the right. */
class SearchHeaderAdapter : RecyclerView.Adapter<SearchHeaderAdapter.ViewHolder>() {

    private var count = 0

    fun setCount(count: Int) {
        if (count == this.count) return
        this.count = count
        notifyItemChanged(0)
    }

    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemSearchHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(count)

    class ViewHolder(private val binding: ItemSearchHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(count: Int) {
            binding.tvCount.text = binding.root.resources.getString(R.string.photo_items, count)
        }
    }
}
