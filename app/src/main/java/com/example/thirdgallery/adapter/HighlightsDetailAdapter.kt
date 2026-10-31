package com.example.thirdgallery.adapter

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.thirdgallery.R
import com.example.thirdgallery.database.MediaTypes
import com.example.thirdgallery.databinding.ItemHighlightHeroBinding
import com.example.thirdgallery.databinding.ItemMediaBinding
import com.example.thirdgallery.databinding.ItemMediaHeaderBinding
import com.example.thirdgallery.helper.HighlightLabels
import com.example.thirdgallery.helper.MediaFormat
import com.example.thirdgallery.helper.MediaUris
import com.example.thirdgallery.model.HighlightRow

/** The highlight detail list: hero with Play, a heading per day and the square media tiles. */
class HighlightsDetailAdapter(
    private val thumbSizePx: Int,
    private val onPlay: () -> Unit,
    private val onOpen: (Long) -> Unit,
) : ListAdapter<HighlightRow, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is HighlightRow.Hero -> TYPE_HERO
        is HighlightRow.Header -> TYPE_HEADER
        is HighlightRow.Tile -> TYPE_TILE
    }

    /** Heroes and day headings take the whole row. */
    fun isFullWidth(position: Int): Boolean = getItemViewType(position) != TYPE_TILE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HERO -> HeroHolder(ItemHighlightHeroBinding.inflate(inflater, parent, false))
            TYPE_HEADER -> HeaderHolder(ItemMediaHeaderBinding.inflate(inflater, parent, false))
            else -> TileHolder(ItemMediaBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is HighlightRow.Hero -> (holder as HeroHolder).bind(row, onPlay)
            is HighlightRow.Header -> (holder as HeaderHolder).bind(row)
            is HighlightRow.Tile -> (holder as TileHolder).bind(row, thumbSizePx, onOpen)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is HeroHolder -> holder.clear()
            is TileHolder -> holder.clear()
        }
        super.onViewRecycled(holder)
    }

    private class HeroHolder(private val binding: ItemHighlightHeroBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: HighlightRow.Hero, onPlay: () -> Unit) {
            val context = binding.root.context
            load(binding.ivBig, row.card.uids.getOrNull(0), BIG_PX)
            load(binding.ivTop, row.card.uids.getOrNull(1), SMALL_PX)
            load(binding.ivBottom, row.card.uids.getOrNull(2), SMALL_PX)
            binding.tvTitle.text = HighlightLabels.title(context, row.card)
            binding.tvSub.text = HighlightLabels.meta(context, row.card, row.count)
            binding.btnPlay.setOnClickListener { onPlay() }
        }

        private fun load(view: ImageView, uid: Long?, sizePx: Int) {
            if (uid == null) {
                Glide.with(view).clear(view)
                view.setImageResource(R.color.language_check_off)
                return
            }
            Glide.with(view)
                .load(MediaUris.ofUid(uid))
                .override(sizePx * 2, sizePx * 2)
                .centerCrop()
                .dontAnimate()
                .placeholder(R.color.language_check_off)
                .into(view)
        }

        fun clear() {
            if (isHostDestroyed(binding.root.context)) return
            listOf(binding.ivBig, binding.ivTop, binding.ivBottom).forEach { Glide.with(it).clear(it) }
        }
    }

    private class HeaderHolder(private val binding: ItemMediaHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HighlightRow.Header) {
            binding.tvTitle.text = MediaFormat.dayTitle(binding.root.context, row.dayKey)
            binding.tvCount.text = row.count.toString()
        }
    }

    private class TileHolder(private val binding: ItemMediaBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: HighlightRow.Tile, sizePx: Int, onOpen: (Long) -> Unit) {
            val media = row.media
            Glide.with(binding.ivThumb)
                .load(MediaUris.of(media.mediaId, media.mediaType))
                .override(sizePx, sizePx)
                .centerCrop()
                .dontAnimate()
                .placeholder(R.color.language_check_off)
                .into(binding.ivThumb)
            val isVideo = media.mediaType == MediaTypes.VIDEO
            binding.tvDuration.visibility = if (isVideo) View.VISIBLE else View.GONE
            if (isVideo) binding.tvDuration.text = MediaFormat.duration(media.durationMs)
            binding.ivFavorite.visibility = if (media.isFavorite) View.VISIBLE else View.GONE
            binding.ivCheck.visibility = View.GONE
            binding.root.setOnClickListener { onOpen(media.uid) }
        }

        fun clear() {
            if (isHostDestroyed(binding.root.context)) return
            Glide.with(binding.ivThumb).clear(binding.ivThumb)
        }
    }

    private companion object {
        const val TYPE_HERO = 0
        const val TYPE_HEADER = 1
        const val TYPE_TILE = 2
        const val BIG_PX = 300
        const val SMALL_PX = 150

        val DIFF = object : DiffUtil.ItemCallback<HighlightRow>() {
            override fun areItemsTheSame(old: HighlightRow, new: HighlightRow): Boolean = when {
                old is HighlightRow.Hero && new is HighlightRow.Hero -> true
                old is HighlightRow.Header && new is HighlightRow.Header -> old.dayKey == new.dayKey
                old is HighlightRow.Tile && new is HighlightRow.Tile -> old.media.uid == new.media.uid
                else -> false
            }

            override fun areContentsTheSame(old: HighlightRow, new: HighlightRow) = old == new
        }

        // The grid is detached while the activity is destroyed; Glide.with() throws then.
        fun isHostDestroyed(context: Context): Boolean {
            var current: Context? = context
            while (current is ContextWrapper) {
                if (current is Activity) return current.isDestroyed
                current = current.baseContext
            }
            return false
        }
    }
}
