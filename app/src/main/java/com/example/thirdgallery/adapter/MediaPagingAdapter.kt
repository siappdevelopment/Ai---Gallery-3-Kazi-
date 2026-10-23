package com.example.thirdgallery.adapter

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.ContextWrapper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.thirdgallery.R
import com.example.thirdgallery.database.MediaEntity
import com.example.thirdgallery.database.MediaTypes
import com.example.thirdgallery.databinding.ItemMediaBinding
import com.example.thirdgallery.databinding.ItemMediaHeaderBinding
import com.example.thirdgallery.helper.MediaFormat
import com.example.thirdgallery.model.MediaUiModel

/**
 * Paged grid adapter: day headers (full width) and square thumbnails. Rows keep their identity
 * through [MediaUiModelCallback], and thumbnails are decoded by Glide at tile size only.
 */
class MediaPagingAdapter(
    private var thumbSizePx: Int,
    private val onOpen: (Long) -> Unit,
) : PagingDataAdapter<MediaUiModel, RecyclerView.ViewHolder>(MediaUiModelCallback) {

    private var dayCounts: Map<Long, Int> = emptyMap()

    /** Tile size changed (grid zoom): rebind the visible tiles so thumbnails match the new size. */
    fun setThumbSize(sizePx: Int) {
        if (sizePx == thumbSizePx) return
        thumbSizePx = sizePx
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    fun isHeader(position: Int): Boolean = peek(position) is MediaUiModel.DayHeader

    /** Updates the per-day counts; only the visible headers are rebound. */
    fun updateCounts(counts: Map<Long, Int>) {
        if (counts == dayCounts) return
        dayCounts = counts
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_COUNT)
    }

    override fun getItemViewType(position: Int): Int =
        if (peek(position) is MediaUiModel.DayHeader) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemMediaHeaderBinding.inflate(inflater, parent, false))
        } else {
            ItemHolder(ItemMediaBinding.inflate(inflater, parent, false), onOpen)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val model = getItem(position)) {
            is MediaUiModel.DayHeader -> (holder as? HeaderHolder)?.bind(model, dayCounts[model.dayKey])
            is MediaUiModel.Item -> (holder as? ItemHolder)?.bind(model.media, thumbSizePx)
            null -> Unit
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_COUNT)) {
            // Only a header shows a count; photo tiles stay untouched (no reload, no flicker).
            val model = peek(position)
            if (holder is HeaderHolder && model is MediaUiModel.DayHeader) {
                holder.bindCount(dayCounts[model.dayKey])
            }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ItemHolder) holder.clear()
        super.onViewRecycled(holder)
    }

    class HeaderHolder(private val binding: ItemMediaHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(model: MediaUiModel.DayHeader, count: Int?) {
            binding.tvTitle.text = MediaFormat.dayTitle(binding.root.context, model.dayKey)
            bindCount(count)
        }

        fun bindCount(count: Int?) {
            binding.tvCount.text = if (count != null && count > 0) count.toString() else ""
        }
    }

    class ItemHolder(
        private val binding: ItemMediaBinding,
        private val onOpen: (Long) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(media: MediaEntity, sizePx: Int) {
            val isVideo = media.mediaType == MediaTypes.VIDEO
            val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = ContentUris.withAppendedId(base, media.mediaId)

            binding.ivThumb.contentDescription = binding.root.context.getString(
                if (isVideo) R.string.photo_filter_video else R.string.photo_filter_photo
            )
            Glide.with(binding.ivThumb)
                .load(uri)
                .override(sizePx, sizePx)
                .centerCrop()
                .dontAnimate()
                .placeholder(R.color.language_check_off)
                .into(binding.ivThumb)

            binding.tvDuration.visibility = if (isVideo) android.view.View.VISIBLE else android.view.View.GONE
            if (isVideo) binding.tvDuration.text = MediaFormat.duration(media.durationMs)
            binding.ivFavorite.visibility = if (media.isFavorite) android.view.View.VISIBLE else android.view.View.GONE
            binding.root.setOnClickListener { onOpen(media.uid) }
        }

        fun clear() {
            // Detaching the adapter in onDestroy() recycles the tiles after the activity is already
            // destroyed; Glide.with() throws then, and its lifecycle has cleared these requests anyway.
            if (isHostDestroyed(binding.root.context)) return
            Glide.with(binding.ivThumb).clear(binding.ivThumb)
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

    private object MediaUiModelCallback : DiffUtil.ItemCallback<MediaUiModel>() {
        override fun areItemsTheSame(old: MediaUiModel, new: MediaUiModel): Boolean = when {
            old is MediaUiModel.Item && new is MediaUiModel.Item -> old.media.uid == new.media.uid
            old is MediaUiModel.DayHeader && new is MediaUiModel.DayHeader -> old.dayKey == new.dayKey
            else -> false
        }

        // The sync token changes on every sync, so it must not count as a visible change.
        override fun areContentsTheSame(old: MediaUiModel, new: MediaUiModel): Boolean =
            if (old is MediaUiModel.Item && new is MediaUiModel.Item) {
                old.media.copy(syncToken = 0) == new.media.copy(syncToken = 0)
            } else {
                old == new
            }
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
        private const val PAYLOAD_COUNT = "count"
    }
}
