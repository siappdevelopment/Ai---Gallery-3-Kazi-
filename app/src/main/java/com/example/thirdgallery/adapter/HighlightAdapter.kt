package com.example.thirdgallery.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.thirdgallery.R
import com.example.thirdgallery.database.HighlightEntity
import com.example.thirdgallery.databinding.ItemHighlightBinding
import com.example.thirdgallery.helper.HomePhotos
import java.text.DateFormatSymbols
import java.util.Locale

class HighlightAdapter : ListAdapter<HighlightEntity, HighlightAdapter.ViewHolder>(DIFF) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemHighlightBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class ViewHolder(private val binding: ItemHighlightBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: HighlightEntity) {
            val context = binding.root.context
            val locale = context.resources.configuration.locales.get(0) ?: Locale.getDefault()
            val months = DateFormatSymbols.getInstance(locale).shortMonths
            binding.ivBig.setImageResource(HomePhotos.res(item.photo1))
            binding.ivTop.setImageResource(HomePhotos.res(item.photo2))
            binding.ivBottom.setImageResource(HomePhotos.res(item.photo3))
            binding.tvMonth.text = months.getOrNull(item.month).orEmpty()
            val photos = context.resources.getQuantityString(R.plurals.intro2_photos, item.photoCount, item.photoCount)
            binding.tvMeta.text = "${item.year} · $photos"
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<HighlightEntity>() {
            override fun areItemsTheSame(old: HighlightEntity, new: HighlightEntity) = old.id == new.id
            override fun areContentsTheSame(old: HighlightEntity, new: HighlightEntity) = old == new
        }
    }
}
