package com.example.thirdgallery.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.thirdgallery.R
import com.example.thirdgallery.database.AlbumRow
import com.example.thirdgallery.databinding.ItemLibraryAlbumBinding
import com.example.thirdgallery.helper.AlbumIcons
import com.example.thirdgallery.helper.MediaUris

/** "Your library" cards: stacked cover of the album's newest photos, name and size. */
class LibraryAlbumAdapter : ListAdapter<AlbumRow, LibraryAlbumAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemLibraryAlbumBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: ViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    class ViewHolder(private val binding: ItemLibraryAlbumBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(album: AlbumRow) {
            val resources = binding.root.resources
            val density = resources.displayMetrics.density
            val widthPx = (132 * density).toInt()
            load(binding.ivCover, album.cover0, widthPx, (148 * density).toInt())
            // The peeking strips only show their top edge, so a small decode is enough.
            load(binding.ivStrip1, album.cover1 ?: album.cover0, widthPx, (24 * density).toInt())
            load(binding.ivStrip2, album.cover2 ?: album.cover0, widthPx, (24 * density).toInt())

            binding.ivAlbumIcon.setImageResource(AlbumIcons.forName(album.bucketName))
            binding.tvAlbumName.text = album.bucketName
            binding.tvAlbumCount.text = resources.getString(R.string.photo_items, album.total)
        }

        fun clear() {
            Glide.with(binding.ivCover).clear(binding.ivCover)
            Glide.with(binding.ivStrip1).clear(binding.ivStrip1)
            Glide.with(binding.ivStrip2).clear(binding.ivStrip2)
        }

        private fun load(view: ImageView, uid: Long?, width: Int, height: Int) {
            if (uid == null) {
                Glide.with(view).clear(view)
                view.setImageDrawable(null)
                return
            }
            Glide.with(view)
                .load(MediaUris.ofUid(uid))
                .override(width, height)
                .centerCrop()
                .dontAnimate()
                .into(view)
        }

    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<AlbumRow>() {
            override fun areItemsTheSame(old: AlbumRow, new: AlbumRow) = old.bucketId == new.bucketId
            override fun areContentsTheSame(old: AlbumRow, new: AlbumRow) = old == new
        }
    }
}
