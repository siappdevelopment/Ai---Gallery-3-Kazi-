package com.example.thirdgallery.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.thirdgallery.databinding.ItemFilterEmptyBinding

/** "Nothing here yet" row shown under the filter bar when a filter matches no media. */
class FilterEmptyAdapter : RecyclerView.Adapter<FilterEmptyAdapter.ViewHolder>() {

    private var visible = false

    fun setVisible(show: Boolean) {
        if (show == visible) return
        visible = show
        if (show) notifyItemInserted(0) else notifyItemRemoved(0)
    }

    override fun getItemCount(): Int = if (visible) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemFilterEmptyBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = Unit

    class ViewHolder(binding: ItemFilterEmptyBinding) : RecyclerView.ViewHolder(binding.root)
}
