package com.example.thirdgallery.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.thirdgallery.R
import com.example.thirdgallery.database.AlbumRow
import com.example.thirdgallery.database.JournalDayRow
import com.example.thirdgallery.database.MediaTypes
import com.example.thirdgallery.databinding.ItemJournalAlbumBinding
import com.example.thirdgallery.databinding.ItemJournalDayBinding
import com.example.thirdgallery.databinding.ItemJournalStripBinding
import com.example.thirdgallery.helper.AlbumIcons
import com.example.thirdgallery.helper.HorizontalSpacingDecoration
import com.example.thirdgallery.helper.MediaFormat
import com.example.thirdgallery.helper.MediaUris

private fun loadInto(view: ImageView, uid: Long?, widthPx: Int, heightPx: Int) {
    if (uid == null) {
        Glide.with(view).clear(view)
        view.setImageDrawable(null)
        return
    }
    Glide.with(view).load(MediaUris.ofUid(uid)).override(widthPx, heightPx).centerCrop().dontAnimate().into(view)
}

/** Compact library cards of the Journal layout (small cover with a pink icon badge). */
class JournalAlbumAdapter : ListAdapter<AlbumRow, JournalAlbumAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemJournalAlbumBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: ViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    class ViewHolder(private val binding: ItemJournalAlbumBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(album: AlbumRow) {
            val px = (44 * binding.root.resources.displayMetrics.density).toInt()
            loadInto(binding.ivCover, album.cover0, px, px)
            binding.ivAlbumIcon.setImageResource(AlbumIcons.forName(album.bucketName))
            binding.tvAlbumName.text = album.bucketName
            binding.tvAlbumCount.text = binding.root.resources.getString(R.string.photo_items, album.total)
        }

        fun clear() {
            Glide.with(binding.ivCover).clear(binding.ivCover)
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<AlbumRow>() {
            override fun areItemsTheSame(old: AlbumRow, new: AlbumRow) = old.bucketId == new.bucketId
            override fun areContentsTheSame(old: AlbumRow, new: AlbumRow) = old == new
        }
    }
}

/** The small tiles under a Journal day's hero photo. */
class JournalStripAdapter : ListAdapter<Long, JournalStripAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemJournalStripBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: ViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    class ViewHolder(private val binding: ItemJournalStripBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(uid: Long) {
            val px = (66 * binding.root.resources.displayMetrics.density).toInt()
            loadInto(binding.ivStrip, uid, px, px)
            binding.videoBadge.visibility = if (MediaTypes.typeOf(uid) == MediaTypes.VIDEO) View.VISIBLE else View.GONE
        }

        fun clear() {
            Glide.with(binding.ivStrip).clear(binding.ivStrip)
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Long>() {
            override fun areItemsTheSame(old: Long, new: Long) = old == new
            override fun areContentsTheSame(old: Long, new: Long) = old == new
        }
    }
}

/**
 * Journal: one card per day (paged by day, so 100,000+ items stay cheap). Each card shows the
 * day's newest item large, the next items as a strip, and Add to album / Share for the whole day.
 */
class JournalPagingAdapter(
    private val onAddToAlbum: (JournalDayRow) -> Unit,
    private val onShare: (JournalDayRow) -> Unit,
) : PagingDataAdapter<JournalDayRow, JournalPagingAdapter.ViewHolder>(DIFF) {

    // One pool for every strip, so tiles scrolled off in one card are reused by the next.
    private val stripPool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 40) }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemJournalDayBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        getItem(position)?.let { holder.bind(it) }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(private val binding: ItemJournalDayBinding) : RecyclerView.ViewHolder(binding.root) {

        private val stripAdapter = JournalStripAdapter()
        private var row: JournalDayRow? = null

        init {
            val density = binding.root.resources.displayMetrics.density
            binding.rvStrip.adapter = stripAdapter
            binding.rvStrip.itemAnimator = null
            binding.rvStrip.setRecycledViewPool(stripPool)
            binding.rvStrip.addItemDecoration(HorizontalSpacingDecoration((4 * density).toInt()))
            binding.btnAddToAlbum.setOnClickListener { row?.let(onAddToAlbum) }
            binding.btnShare.setOnClickListener { row?.let(onShare) }
        }

        fun bind(item: JournalDayRow) {
            row = item
            val context = binding.root.context
            val metrics = context.resources.displayMetrics
            loadInto(binding.ivHero, item.heroUid, metrics.widthPixels, (210 * metrics.density).toInt())

            binding.tvTitle.text = MediaFormat.dayTitle(context, item.dayKey)
            binding.tvCount.text = context.getString(R.string.photo_items, item.total)
            val place = item.place.orEmpty()
            binding.tvPlace.visibility = if (place.isEmpty()) View.GONE else View.VISIBLE
            binding.tvPlace.text = place

            val strip = item.strip.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }
            binding.rvStrip.visibility = if (strip.isEmpty()) View.GONE else View.VISIBLE
            stripAdapter.submitList(strip)
            binding.rvStrip.scrollToPosition(0)
        }

        fun clear() {
            Glide.with(binding.ivHero).clear(binding.ivHero)
            row = null
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<JournalDayRow>() {
            override fun areItemsTheSame(old: JournalDayRow, new: JournalDayRow) = old.dayKey == new.dayKey
            override fun areContentsTheSame(old: JournalDayRow, new: JournalDayRow) = old == new
        }
    }
}
