package com.photovideo.gallerykz.memories.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.databinding.ItemSuggestionPickHeaderBinding
import com.photovideo.gallerykz.memories.databinding.ItemSuggestionTileBinding
import com.photovideo.gallerykz.memories.helper.MediaUris

/** Top of a suggestion screen: back, the title and what it is about, and "N selected" with Select / Deselect all. */
class SuggestionPickHeaderAdapter(
    private val onBack: () -> Unit,
    private val onToggleAll: () -> Unit,
) : RecyclerView.Adapter<SuggestionPickHeaderAdapter.ViewHolder>() {

    private var title = ""
    private var description = ""
    private var selected = 0
    private var allSelected = true

    fun update(title: String, description: String, selected: Int, allSelected: Boolean) {
        if (title == this.title && description == this.description && selected == this.selected && allSelected == this.allSelected) return
        this.title = title
        this.description = description
        this.selected = selected
        this.allSelected = allSelected
        notifyItemChanged(0)
    }

    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemSuggestionPickHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val b = holder.binding
        b.tvTitle.text = title
        b.tvDesc.text = description
        b.tvSelected.text = b.root.resources.getString(R.string.selection_selected, selected)
        b.btnAll.setText(if (allSelected) R.string.suggest_deselect_all else R.string.selection_select_all)
        b.btnBack.setOnClickListener { onBack() }
        b.btnAll.setOnClickListener { onToggleAll() }
    }

    class ViewHolder(val binding: ItemSuggestionPickHeaderBinding) : RecyclerView.ViewHolder(binding.root)
}

/** Square tiles of the suggested photos with the pink check (design): selected photos are checked and a little smaller. */
class SuggestionTileAdapter(
    private val thumbSizePx: Int,
    private val onToggle: (Long) -> Unit,
) : ListAdapter<Long, SuggestionTileAdapter.ViewHolder>(DIFF) {

    private var selected: Set<Long> = emptySet()

    fun setSelected(selected: Set<Long>) {
        if (selected == this.selected) return
        this.selected = selected
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemSuggestionTileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val uid = getItem(position)
        Glide.with(holder.binding.ivThumb)
            .load(MediaUris.ofUid(uid))
            .override(thumbSizePx, thumbSizePx)
            .centerCrop()
            .dontAnimate()
            .placeholder(R.color.language_check_off)
            .into(holder.binding.ivThumb)
        holder.binding.root.setOnClickListener { onToggle(uid) }
        holder.draw(uid in selected)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) holder.draw(getItem(position) in selected) else super.onBindViewHolder(holder, position, payloads)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        clearFace(holder.binding.ivThumb)
        super.onViewRecycled(holder)
    }

    class ViewHolder(val binding: ItemSuggestionTileBinding) : RecyclerView.ViewHolder(binding.root) {
        fun draw(selected: Boolean) = drawSelection(binding.ivCheck, binding.ivThumb, true, selected)
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Long>() {
            override fun areItemsTheSame(old: Long, new: Long) = old == new
            override fun areContentsTheSame(old: Long, new: Long) = old == new
        }
    }
}
