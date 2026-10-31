package com.example.thirdgallery.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.ItemHighlightBinding
import com.example.thirdgallery.helper.HighlightLabels
import com.example.thirdgallery.helper.MediaUris
import com.example.thirdgallery.model.HighlightCard

/** The Highlights cards of For you: a three-photo collage, the big title and "period · N photos". A tap opens the card. */
class HighlightAdapter(
    private val onOpen: (HighlightCard) -> Unit,
) : ListAdapter<HighlightCard, HighlightAdapter.ViewHolder>(DIFF) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemHighlightBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position), onOpen)

    override fun onViewRecycled(holder: ViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    class ViewHolder(private val binding: ItemHighlightBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(card: HighlightCard, onOpen: (HighlightCard) -> Unit) {
            val context = binding.root.context
            load(binding.ivBig, card.uids.getOrNull(0), BIG_PX)
            load(binding.ivTop, card.uids.getOrNull(1), SMALL_PX)
            load(binding.ivBottom, card.uids.getOrNull(2), SMALL_PX)
            binding.tvMonth.text = HighlightLabels.title(context, card)
            binding.tvMeta.text = HighlightLabels.meta(context, card, card.count)
            binding.root.setOnClickListener { onOpen(card) }
        }

        private fun load(view: ImageView, uid: Long?, sizePx: Int) {
            if (uid == null) {
                Glide.with(view).clear(view)
                view.setImageResource(R.color.language_check_off)
                return
            }
            Glide.with(view)
                .load(MediaUris.ofUid(uid))
                .override(sizePx, sizePx)
                .centerCrop()
                .dontAnimate()
                .placeholder(R.color.language_check_off)
                .into(view)
        }

        fun clear() {
            val context = binding.root.context
            if (context is android.app.Activity && context.isDestroyed) return
            listOf(binding.ivBig, binding.ivTop, binding.ivBottom).forEach { Glide.with(it).clear(it) }
        }
    }

    private companion object {
        const val BIG_PX = 400
        const val SMALL_PX = 200

        val DIFF = object : DiffUtil.ItemCallback<HighlightCard>() {
            override fun areItemsTheSame(old: HighlightCard, new: HighlightCard) = old.id == new.id
            override fun areContentsTheSame(old: HighlightCard, new: HighlightCard) = old == new
        }
    }
}
