package com.photovideo.gallerykz.memories.adapter

import com.photovideo.gallerykz.memories.helper.clearImage
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.databinding.ItemAlbumPickBinding
import com.photovideo.gallerykz.memories.helper.MediaUris
import com.photovideo.gallerykz.memories.viewmodel.PickAlbum

/** Rows of the "Add to album" sheet: cover, name and item count. Every album is listed. */
class PickAlbumAdapter(
    private val onPick: (PickAlbum) -> Unit,
) : ListAdapter<PickAlbum, PickAlbumAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemAlbumPickBinding.inflate(LayoutInflater.from(parent.context), parent, false), onPick)

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class ViewHolder(
        private val binding: ItemAlbumPickBinding,
        private val onPick: (PickAlbum) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(album: PickAlbum) {
            binding.tvAlbumName.text = album.name
            binding.tvAlbumCount.text = binding.root.resources.getString(R.string.photo_items, album.total)
            val cover = album.cover
            if (cover != null) {
                val px = (48 * binding.root.resources.displayMetrics.density).toInt()
                Glide.with(binding.ivAlbumCover).load(MediaUris.ofUid(cover)).override(px, px).centerCrop().dontAnimate()
                    .into(binding.ivAlbumCover)
            } else {
                clearImage(binding.ivAlbumCover)
                binding.ivAlbumCover.setImageDrawable(null)
            }
            binding.root.setOnClickListener { onPick(album) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<PickAlbum>() {
            override fun areItemsTheSame(old: PickAlbum, new: PickAlbum) = old.name.lowercase() == new.name.lowercase()
            override fun areContentsTheSame(old: PickAlbum, new: PickAlbum) = old == new
        }
    }
}
