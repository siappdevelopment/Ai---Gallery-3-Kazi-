package com.photovideo.gallerykz.memories.adapter

import com.photovideo.gallerykz.memories.helper.clearImage
import android.app.Activity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.database.PlaceSummary
import com.photovideo.gallerykz.memories.databinding.ItemPlaceBinding
import com.photovideo.gallerykz.memories.helper.MediaUris
import com.photovideo.gallerykz.memories.helper.PlaceText

/** The Places row of For you: small cards with the newest photo of the place, its name and its photo count. */
class PlaceAdapter(
    private val onOpen: (PlaceSummary) -> Unit,
) : ListAdapter<PlaceSummary, PlaceAdapter.ViewHolder>(DIFF) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).cell

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemPlaceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position), onOpen)

    override fun onViewRecycled(holder: ViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    class ViewHolder(private val binding: ItemPlaceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PlaceSummary, onOpen: (PlaceSummary) -> Unit) {
            val context = binding.root.context
            val cover = item.coverUid
            if (cover == null) {
                clear()
                binding.ivPhoto.setImageResource(R.color.language_check_off)
            } else {
                Glide.with(binding.ivPhoto)
                    .load(MediaUris.ofUid(cover))
                    .override(PHOTO_PX, PHOTO_PX)
                    .centerCrop()
                    .dontAnimate()
                    .placeholder(R.color.language_check_off)
                    .into(binding.ivPhoto)
            }
            binding.tvName.text = PlaceText.name(context, item)
            binding.tvCount.text = context.resources.getQuantityString(R.plurals.intro2_photos, item.itemCount, item.itemCount)
            binding.root.setOnClickListener { onOpen(item) }
        }

        fun clear() {
            val context = binding.root.context
            if (context is Activity && context.isDestroyed) return
            clearImage(binding.ivPhoto)
        }
    }

    private companion object {
        const val PHOTO_PX = 400

        val DIFF = object : DiffUtil.ItemCallback<PlaceSummary>() {
            override fun areItemsTheSame(old: PlaceSummary, new: PlaceSummary) = old.cell == new.cell
            override fun areContentsTheSame(old: PlaceSummary, new: PlaceSummary) = old == new
        }
    }
}
