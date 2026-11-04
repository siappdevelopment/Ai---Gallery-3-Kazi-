package com.photovideo.gallerykz.memories.adapter

import com.photovideo.gallerykz.memories.helper.clearImage
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.databinding.ItemTrashBinding
import com.photovideo.gallerykz.memories.helper.MediaUris
import com.photovideo.gallerykz.memories.repo.BinItem

/** Square tiles of the Bin, dimmed, each with the days left before it is deleted for good. */
class TrashAdapter(
    private var thumbSizePx: Int,
) : ListAdapter<BinItem, TrashAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemTrashBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    var selectionMode = false
        private set
    private val selected = LinkedHashSet<Long>()

    /** Called whenever the selection mode or the selected set changes. */
    var onSelectionChanged: (() -> Unit)? = null

    /** Called when an item is tapped outside selection mode (opens it full screen). */
    var onItemClick: ((BinItem) -> Unit)? = null

    val selectedUids: Set<Long> get() = selected

    fun setSelectionMode(enabled: Boolean) {
        if (selectionMode == enabled && (enabled || selected.isEmpty())) return
        selectionMode = enabled
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    fun selectAll() {
        val all = currentList.map { it.uid }
        if (selected.size == all.size) selected.clear() else selected.addAll(all)
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    private fun toggle(uid: Long, position: Int) {
        if (!selected.add(uid)) selected.remove(uid)
        notifyItemChanged(position)
        onSelectionChanged?.invoke()
    }

    override fun submitList(list: List<BinItem>?) {
        // Drop selections of items that are gone (restored or deleted).
        if (selected.retainAll((list ?: emptyList()).map { it.uid }.toSet())) onSelectionChanged?.invoke()
        super.submitList(list)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, thumbSizePx, selectionMode, item.uid in selected)
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            if (selectionMode) toggle(item.uid, pos) else onItemClick?.invoke(item)
        }
        holder.itemView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
            if (!selectionMode) {
                selectionMode = true
                selected.clear()
                selected.add(item.uid)
                notifyDataSetChanged()
                onSelectionChanged?.invoke()
            } else {
                toggle(item.uid, pos)
            }
            true
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    class ViewHolder(private val binding: ItemTrashBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: BinItem, sizePx: Int, selecting: Boolean, isSelected: Boolean) {
            binding.ivCheck.visibility = if (selecting) View.VISIBLE else View.GONE
            if (isSelected) {
                binding.ivCheck.setImageResource(R.drawable.ic_check_circle)
                binding.ivCheck.setColorFilter(0xFFFF4D7E.toInt())
            } else {
                binding.ivCheck.setImageResource(R.drawable.bg_selection_check_off)
                binding.ivCheck.clearColorFilter()
            }
            binding.ivThumb.alpha = if (isSelected) 0.6f else 1f
            Glide.with(binding.ivThumb)
                .load(MediaUris.ofUid(item.uid))
                .override(sizePx, sizePx)
                .centerCrop()
                .dontAnimate()
                .placeholder(R.color.language_check_off)
                .into(binding.ivThumb)
            val days = daysLeft(item.expiresMs)
            binding.tvDays.visibility = if (days == null) View.GONE else View.VISIBLE
            if (days != null) binding.tvDays.text = binding.root.resources.getString(R.string.bin_days_left, days)
        }

        fun clear() {
            // The grid is detached while the activity is destroyed; Glide.with() throws then.
            if (isHostDestroyed(binding.root.context)) return
            clearImage(binding.ivThumb)
        }

        private fun daysLeft(expiresMs: Long): Int? {
            if (expiresMs <= 0L) return null
            val left = expiresMs - System.currentTimeMillis()
            return ((left + DAY_MS - 1) / DAY_MS).toInt().coerceAtLeast(0)
        }

        private fun isHostDestroyed(context: Context): Boolean {
            var current: Context? = context
            while (current is ContextWrapper) {
                if (current is Activity) return current.isDestroyed
                current = current.baseContext
            }
            return false
        }
    }

    private companion object {
        const val DAY_MS = 86_400_000L

        val DIFF = object : DiffUtil.ItemCallback<BinItem>() {
            override fun areItemsTheSame(old: BinItem, new: BinItem) = old.uid == new.uid
            override fun areContentsTheSame(old: BinItem, new: BinItem) = old == new
        }
    }
}
