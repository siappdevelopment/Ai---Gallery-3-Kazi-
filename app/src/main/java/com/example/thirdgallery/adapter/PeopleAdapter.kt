package com.example.thirdgallery.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.thirdgallery.database.PersonEntity
import com.example.thirdgallery.databinding.ItemPersonBinding
import com.example.thirdgallery.helper.HomePhotos

class PeopleAdapter : ListAdapter<PersonEntity, PeopleAdapter.ViewHolder>(DIFF) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemPersonBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class ViewHolder(private val binding: ItemPersonBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PersonEntity) {
            binding.ivAvatar.setImageResource(HomePhotos.res(item.photoIndex))
            binding.tvName.text = item.name
            binding.tvCount.text = item.photoCount.toString()
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<PersonEntity>() {
            override fun areItemsTheSame(old: PersonEntity, new: PersonEntity) = old.id == new.id
            override fun areContentsTheSame(old: PersonEntity, new: PersonEntity) = old == new
        }
    }
}
