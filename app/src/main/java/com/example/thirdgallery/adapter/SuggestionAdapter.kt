package com.example.thirdgallery.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.thirdgallery.R
import com.example.thirdgallery.database.SuggestionEntity
import com.example.thirdgallery.database.SuggestionType
import com.example.thirdgallery.databinding.ItemSuggestionBinding
import com.example.thirdgallery.helper.HomePhotos

class SuggestionAdapter : ListAdapter<SuggestionEntity, SuggestionAdapter.ViewHolder>(DIFF) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class ViewHolder(private val binding: ItemSuggestionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SuggestionEntity) {
            binding.ivThumb1.setImageResource(HomePhotos.res(item.photo1))
            binding.ivThumb2.setImageResource(HomePhotos.res(item.photo2))
            binding.ivThumb3.setImageResource(HomePhotos.res(item.photo3))
            val (title, subtitle) = when (item.type) {
                SuggestionType.ALBUM -> R.string.home_sug_album_title to R.string.home_sug_album_sub
                SuggestionType.BURSTS -> R.string.home_sug_bursts_title to R.string.home_sug_bursts_sub
                else -> R.string.home_sug_fav_title to R.string.home_sug_fav_sub
            }
            binding.tvTitle.setText(title)
            binding.tvSubtitle.setText(subtitle)
            binding.tvCount.text = item.count.toString()
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<SuggestionEntity>() {
            override fun areItemsTheSame(old: SuggestionEntity, new: SuggestionEntity) = old.id == new.id
            override fun areContentsTheSame(old: SuggestionEntity, new: SuggestionEntity) = old == new
        }
    }
}
