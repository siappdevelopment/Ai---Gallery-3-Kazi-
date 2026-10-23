package com.example.thirdgallery.adapter

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
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.ItemTrashBinding
import com.example.thirdgallery.helper.MediaUris
import com.example.thirdgallery.repo.BinItem

/** Square tiles of the Bin, dimmed, each with the days left before it is deleted for good. */
class TrashAdapter(
    private var thumbSizePx: Int,
) : ListAdapter<BinItem, TrashAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemTrashBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position), thumbSizePx)

    override fun onViewRecycled(holder: ViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    class ViewHolder(private val binding: ItemTrashBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: BinItem, sizePx: Int) {
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
            Glide.with(binding.ivThumb).clear(binding.ivThumb)
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
