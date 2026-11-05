package com.photovideo.gallerykz.memories.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.database.AlbumRow
import com.photovideo.gallerykz.memories.databinding.ItemGalleryHeaderBinding
import com.photovideo.gallerykz.memories.helper.HorizontalSpacingDecoration
import com.photovideo.gallerykz.memories.model.MediaFilter

/**
 * Single full-width row above the photo grid in the Classic layout: the "Your library" albums
 * and the All / Photo / Video / Favorite filter. It scrolls together with the grid.
 */
class GalleryHeaderAdapter(
    private val onFilterSelected: (MediaFilter) -> Unit,
    private val onAllAlbums: () -> Unit,
    private val onOpenAlbum: (AlbumRow) -> Unit,
    private val onSortClick: () -> Unit,
) : RecyclerView.Adapter<GalleryHeaderAdapter.ViewHolder>() {

    private var sortLabel = ""

    /** The text of the sort button ("Newest first", "Name A–Z", ...). */
    fun setSortLabel(label: String) {
        if (label == sortLabel) return
        sortLabel = label
        if (visible) notifyItemChanged(0)
    }

    private var visible = false
    private var total = 0
    private var albums: List<AlbumRow> = emptyList()
    private var filter = MediaFilter.ALL

    /** Shown or hidden without touching the photo list. */
    fun setVisible(show: Boolean) {
        if (show == visible) return
        visible = show
        if (show) notifyItemInserted(0) else notifyItemRemoved(0)
    }

    fun setContent(total: Int, albums: List<AlbumRow>) {
        if (total == this.total && albums == this.albums) return
        this.total = total
        this.albums = albums
        if (visible) notifyItemChanged(0)
    }

    fun setFilter(filter: MediaFilter) {
        if (filter == this.filter) return
        this.filter = filter
        if (visible) notifyItemChanged(0)
    }

    override fun getItemCount(): Int = if (visible) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemGalleryHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(total, albums, filter)

    inner class ViewHolder(private val binding: ItemGalleryHeaderBinding) : RecyclerView.ViewHolder(binding.root) {

        private val albumAdapter = LibraryAlbumAdapter(onOpenAlbum)

        init {
            val density = binding.root.resources.displayMetrics.density
            binding.rvAlbums.adapter = albumAdapter
            binding.rvAlbums.itemAnimator = null
            binding.rvAlbums.addItemDecoration(HorizontalSpacingDecoration((10 * density).toInt()))
            binding.tvAllAlbums.setOnClickListener { onAllAlbums() }
            binding.tvSort.setOnClickListener { onSortClick() }
            binding.filterBar.tabAll.setOnClickListener { onFilterSelected(MediaFilter.ALL) }
            binding.filterBar.tabPhotos.setOnClickListener { onFilterSelected(MediaFilter.PHOTOS) }
            binding.filterBar.tabVideos.setOnClickListener { onFilterSelected(MediaFilter.VIDEOS) }
            binding.filterBar.tabFavorites.setOnClickListener { onFilterSelected(MediaFilter.FAVORITES) }
        }

        fun bind(total: Int, albums: List<AlbumRow>, filter: MediaFilter) {
            val hasAlbums = albums.isNotEmpty()
            binding.librarySection.visibility = if (hasAlbums) View.VISIBLE else View.GONE
            binding.tvLibraryCount.text = binding.root.resources.getString(R.string.photo_items, total)
            albumAdapter.submitList(albums)

            // 16dp between the albums and the filter; without albums the filter sits at the top.
            val density = binding.root.resources.displayMetrics.density
            (binding.filterBar.root.layoutParams as? MarginLayoutParams)?.let {
                it.topMargin = ((if (hasAlbums) 16 else 2) * density).toInt()
                binding.filterBar.root.layoutParams = it
            }

            binding.tvSort.text = sortLabel
            binding.filterBar.tabAll.isSelected = filter == MediaFilter.ALL
            binding.filterBar.tabPhotos.isSelected = filter == MediaFilter.PHOTOS
            binding.filterBar.tabVideos.isSelected = filter == MediaFilter.VIDEOS
            binding.filterBar.tabFavorites.isSelected = filter == MediaFilter.FAVORITES
        }
    }
}
