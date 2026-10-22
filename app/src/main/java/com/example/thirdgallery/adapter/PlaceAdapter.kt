package com.example.thirdgallery.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.thirdgallery.R
import com.example.thirdgallery.database.PlaceEntity
import com.example.thirdgallery.databinding.ItemPlaceBinding
import com.example.thirdgallery.helper.HomePhotos

class PlaceAdapter : ListAdapter<PlaceEntity, PlaceAdapter.ViewHolder>(DIFF) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemPlaceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class ViewHolder(private val binding: ItemPlaceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PlaceEntity) {
            binding.ivPhoto.setImageResource(HomePhotos.res(item.photoIndex))
            binding.tvName.text = item.name
            binding.tvCount.text = binding.root.resources
                .getQuantityString(R.plurals.intro2_photos, item.photoCount, item.photoCount)
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<PlaceEntity>() {
            override fun areItemsTheSame(old: PlaceEntity, new: PlaceEntity) = old.id == new.id
            override fun areContentsTheSame(old: PlaceEntity, new: PlaceEntity) = old == new
        }
    }
}
