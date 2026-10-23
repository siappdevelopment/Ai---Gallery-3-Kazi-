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
import com.example.thirdgallery.database.MediaEntity
import com.example.thirdgallery.database.MediaTypes
import com.example.thirdgallery.databinding.ItemMosaicAlbumBinding
import com.example.thirdgallery.databinding.ItemMosaicHeaderBinding
import com.example.thirdgallery.databinding.ItemMosaicHeroBinding
import com.example.thirdgallery.databinding.ItemMosaicSpacerBinding
import com.example.thirdgallery.databinding.ItemMosaicTileBinding
import com.example.thirdgallery.databinding.ItemMosaicTopBinding
import com.example.thirdgallery.helper.AlbumIcons
import com.example.thirdgallery.helper.MediaFormat
import com.example.thirdgallery.helper.MediaUris
import com.example.thirdgallery.model.MediaFilter
import com.example.thirdgallery.model.MosaicUi

private fun loadThumb(view: ImageView, uid: Long?, widthPx: Int, heightPx: Int) {
    if (uid == null) {
        Glide.with(view).clear(view)
        view.setImageDrawable(null)
        return
    }
    Glide.with(view)
        .load(MediaUris.ofUid(uid))
        .override(widthPx, heightPx)
        .centerCrop()
        .dontAnimate()
        .placeholder(R.color.language_check_off)
        .into(view)
}

/** "Your library" 2 x 2 album cards of the Mosaic layout. */
class MosaicAlbumAdapter(
    private val onOpen: (AlbumRow) -> Unit,
) : ListAdapter<AlbumRow, MosaicAlbumAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemMosaicAlbumBinding.inflate(LayoutInflater.from(parent.context), parent, false), onOpen)

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: ViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    class ViewHolder(
        private val binding: ItemMosaicAlbumBinding,
        private val onOpen: (AlbumRow) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(album: AlbumRow) {
            val density = binding.root.resources.displayMetrics.density
            loadThumb(binding.ivCover, album.cover0, (200 * density).toInt(), (104 * density).toInt())
            binding.ivAlbumIcon.setImageResource(AlbumIcons.forName(album.bucketName))
            binding.tvAlbumName.text = album.bucketName
            binding.tvAlbumCount.text = binding.root.resources.getString(R.string.photo_items, album.total)
            binding.root.setOnClickListener { onOpen(album) }
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

/** Single full-width row above the Mosaic grid: library cards + the filter bar. */
class MosaicTopAdapter(
    private val onFilterSelected: (MediaFilter) -> Unit,
    private val onAllAlbums: () -> Unit,
    private val onOpenAlbum: (AlbumRow) -> Unit,
    /** true = small Journal cards instead of the tall Mosaic cards. */
    private val compactCards: Boolean = false,
) : RecyclerView.Adapter<MosaicTopAdapter.ViewHolder>() {

    private var visible = false
    private var albums: List<AlbumRow> = emptyList()
    private var filter = MediaFilter.ALL

    fun setVisible(show: Boolean) {
        if (show == visible) return
        visible = show
        if (show) notifyItemInserted(0) else notifyItemRemoved(0)
    }

    fun setAlbums(albums: List<AlbumRow>) {
        // The design shows four cards (2 x 2).
        val shown = albums.take(MAX_ALBUMS)
        if (shown == this.albums) return
        this.albums = shown
        if (visible) notifyItemChanged(0)
    }

    fun setFilter(filter: MediaFilter) {
        if (filter == this.filter) return
        this.filter = filter
        if (visible) notifyItemChanged(0)
    }

    override fun getItemCount(): Int = if (visible) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemMosaicTopBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(albums, filter)

    inner class ViewHolder(private val binding: ItemMosaicTopBinding) : RecyclerView.ViewHolder(binding.root) {

        private val albumAdapter: ListAdapter<AlbumRow, out RecyclerView.ViewHolder> =
            if (compactCards) JournalAlbumAdapter(onOpenAlbum) else MosaicAlbumAdapter(onOpenAlbum)

        init {
            binding.rvAlbums.adapter = albumAdapter
            binding.rvAlbums.itemAnimator = null
            binding.tvAllAlbums.setOnClickListener { onAllAlbums() }
            binding.filterBar.tabAll.setOnClickListener { onFilterSelected(MediaFilter.ALL) }
            binding.filterBar.tabPhotos.setOnClickListener { onFilterSelected(MediaFilter.PHOTOS) }
            binding.filterBar.tabVideos.setOnClickListener { onFilterSelected(MediaFilter.VIDEOS) }
            binding.filterBar.tabFavorites.setOnClickListener { onFilterSelected(MediaFilter.FAVORITES) }
        }

        fun bind(albums: List<AlbumRow>, filter: MediaFilter) {
            val hasAlbums = albums.isNotEmpty()
            binding.librarySection.visibility = if (hasAlbums) View.VISIBLE else View.GONE
            albumAdapter.submitList(albums)

            val density = binding.root.resources.displayMetrics.density
            (binding.filterBar.root.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.topMargin = ((if (hasAlbums) 12 else 2) * density).toInt()
                binding.filterBar.root.layoutParams = it
            }
            binding.filterBar.tabAll.isSelected = filter == MediaFilter.ALL
            binding.filterBar.tabPhotos.isSelected = filter == MediaFilter.PHOTOS
            binding.filterBar.tabVideos.isSelected = filter == MediaFilter.VIDEOS
            binding.filterBar.tabFavorites.isSelected = filter == MediaFilter.FAVORITES
        }
    }

    private companion object {
        const val MAX_ALBUMS = 4
    }
}

/**
 * Mosaic grid (3 columns): day headers, a hero block per day (big tile + two small ones), wide
 * tiles and normal tiles. Rows of a day are laid out by their position inside that day (SQL rank),
 * so paging never needs to know a whole day.
 */
class MosaicPagingAdapter(
    private var widthPx: Int,
    private val onOpen: (Long) -> Unit,
) : PagingDataAdapter<MosaicUi, RecyclerView.ViewHolder>(MosaicCallback) {

    private var dayCounts: Map<Long, Int> = emptyMap()

    /** Full width of the grid in pixels; tile sizes (and their thumbnails) derive from it. */
    fun setWidth(px: Int) {
        if (px == widthPx) return
        widthPx = px
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    fun updateCounts(counts: Map<Long, Int>) {
        if (counts == dayCounts) return
        dayCounts = counts
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_COUNT)
    }

    /** Columns the row at [index] takes in the 3-column grid (header, hero, spacer: 3; wide: 2; tile: 1). */
    fun spanAt(index: Int, columns: Int): Int = when (val item = peek(index)) {
        is MosaicUi.Tile -> if (item.wide) (columns - 1).coerceAtLeast(1) else 1
        else -> columns
    }

    override fun getItemViewType(position: Int): Int = when (val item = peek(position)) {
        is MosaicUi.Header -> TYPE_HEADER
        is MosaicUi.Hero -> TYPE_HERO
        is MosaicUi.Spacer -> TYPE_SPACER
        is MosaicUi.Tile -> if (item.wide) TYPE_WIDE else TYPE_TILE
        null -> TYPE_SPACER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(ItemMosaicHeaderBinding.inflate(inflater, parent, false))
            TYPE_HERO -> HeroHolder(ItemMosaicHeroBinding.inflate(inflater, parent, false))
            TYPE_SPACER -> SpacerHolder(ItemMosaicSpacerBinding.inflate(inflater, parent, false))
            else -> TileHolder(ItemMosaicTileBinding.inflate(inflater, parent, false), wide = viewType == TYPE_WIDE)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val third = (widthPx / 3).coerceAtLeast(1)
        when (val item = getItem(position)) {
            is MosaicUi.Header -> (holder as? HeaderHolder)?.bind(item, dayCounts[item.dayKey])
            is MosaicUi.Hero -> (holder as? HeroHolder)?.bind(item, third)
            is MosaicUi.Tile -> (holder as? TileHolder)?.bind(item.media, third)
            is MosaicUi.Spacer, null -> Unit
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_COUNT)) {
            val item = peek(position)
            if (holder is HeaderHolder && item is MosaicUi.Header) holder.bindCount(dayCounts[item.dayKey])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is HeroHolder -> holder.clear()
            is TileHolder -> holder.clear()
        }
        super.onViewRecycled(holder)
    }

    private fun bindBadges(duration: android.widget.TextView, favorite: View, media: MediaEntity) {
        val isVideo = media.mediaType == MediaTypes.VIDEO
        duration.visibility = if (isVideo) View.VISIBLE else View.GONE
        if (isVideo) duration.text = MediaFormat.duration(media.durationMs)
        favorite.visibility = if (media.isFavorite) View.VISIBLE else View.GONE
    }

    inner class HeaderHolder(private val binding: ItemMosaicHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MosaicUi.Header, count: Int?) {
            binding.tvTitle.text = MediaFormat.dayTitle(binding.root.context, item.dayKey)
            binding.placeRow.visibility = if (item.place.isEmpty()) View.GONE else View.VISIBLE
            binding.tvPlace.text = item.place
            bindCount(count)
        }

        fun bindCount(count: Int?) {
            binding.tvCount.text =
                if (count != null && count > 0) binding.root.resources.getString(R.string.photo_items, count) else ""
        }
    }

    inner class HeroHolder(private val binding: ItemMosaicHeroBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MosaicUi.Hero, third: Int) {
            loadThumb(binding.ivBig, item.media.uid, third * 2, third * 2)
            loadThumb(binding.ivSmall1, item.next1, third, third)
            loadThumb(binding.ivSmall2, item.next2, third, third)
            bindBadges(binding.tvDuration, binding.ivFavorite, item.media)
            binding.ivBig.setOnClickListener { onOpen(item.media.uid) }
            binding.ivSmall1.setOnClickListener { item.next1?.let(onOpen) }
            binding.ivSmall2.setOnClickListener { item.next2?.let(onOpen) }
        }

        fun clear() {
            Glide.with(binding.ivBig).clear(binding.ivBig)
            Glide.with(binding.ivSmall1).clear(binding.ivSmall1)
            Glide.with(binding.ivSmall2).clear(binding.ivSmall2)
        }
    }

    inner class TileHolder(private val binding: ItemMosaicTileBinding, private val wide: Boolean) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.ivThumb.ratio = if (wide) 2f else 1f
        }

        fun bind(media: MediaEntity, third: Int) {
            loadThumb(binding.ivThumb, media.uid, if (wide) third * 2 else third, third)
            bindBadges(binding.tvDuration, binding.ivFavorite, media)
            binding.root.setOnClickListener { onOpen(media.uid) }
        }

        fun clear() {
            Glide.with(binding.ivThumb).clear(binding.ivThumb)
        }
    }

    class SpacerHolder(binding: ItemMosaicSpacerBinding) : RecyclerView.ViewHolder(binding.root)

    private object MosaicCallback : DiffUtil.ItemCallback<MosaicUi>() {
        override fun areItemsTheSame(old: MosaicUi, new: MosaicUi): Boolean = when {
            old is MosaicUi.Header && new is MosaicUi.Header -> old.dayKey == new.dayKey
            old is MosaicUi.Content && new is MosaicUi.Content -> old.media.uid == new.media.uid
            else -> false
        }

        // The sync token changes on every sync and must not count as a visible change.
        override fun areContentsTheSame(old: MosaicUi, new: MosaicUi): Boolean = when {
            old is MosaicUi.Tile && new is MosaicUi.Tile ->
                old.wide == new.wide && old.media.copy(syncToken = 0) == new.media.copy(syncToken = 0)
            old is MosaicUi.Hero && new is MosaicUi.Hero ->
                old.next1 == new.next1 && old.next2 == new.next2 &&
                    old.media.copy(syncToken = 0) == new.media.copy(syncToken = 0)
            old is MosaicUi.Spacer && new is MosaicUi.Spacer -> true
            else -> old == new
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_HERO = 1
        private const val TYPE_TILE = 2
        private const val TYPE_WIDE = 3
        private const val TYPE_SPACER = 4
        private const val PAYLOAD_COUNT = "count"
    }
}
