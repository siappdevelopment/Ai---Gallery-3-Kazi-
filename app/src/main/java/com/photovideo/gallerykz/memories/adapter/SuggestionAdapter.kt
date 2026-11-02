package com.photovideo.gallerykz.memories.adapter

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.databinding.ItemSuggestionBinding
import com.photovideo.gallerykz.memories.helper.MediaUris
import com.photovideo.gallerykz.memories.model.SuggestionCard
import com.photovideo.gallerykz.memories.model.SuggestionKind

/** The suggestion rows of For you: three real photos, the title, a line about what it holds and how many photos. */
class SuggestionAdapter(
    private val onOpen: (SuggestionCard) -> Unit,
) : ListAdapter<SuggestionCard, SuggestionAdapter.ViewHolder>(DIFF) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).kind.ordinal.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position), onOpen)

    override fun onViewRecycled(holder: ViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    class ViewHolder(private val binding: ItemSuggestionBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(card: SuggestionCard, onOpen: (SuggestionCard) -> Unit) {
            val context = binding.root.context
            load(binding.ivThumb1, card.covers.getOrNull(0))
            load(binding.ivThumb2, card.covers.getOrNull(1))
            load(binding.ivThumb3, card.covers.getOrNull(2))
            when (card.kind) {
                SuggestionKind.ALBUM -> {
                    binding.tvTitle.setText(R.string.home_sug_album_title)
                    val range = DateUtils.formatDateRange(
                        context, card.fromMs, card.toMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR,
                    )
                    binding.tvSubtitle.text = context.getString(R.string.suggest_album_row_sub, range)
                }
                SuggestionKind.BURSTS -> {
                    binding.tvTitle.setText(R.string.home_sug_bursts_title)
                    binding.tvSubtitle.setText(R.string.home_sug_bursts_sub)
                }
                SuggestionKind.FAVORITES -> {
                    binding.tvTitle.setText(R.string.home_sug_fav_title)
                    binding.tvSubtitle.setText(R.string.home_sug_fav_sub)
                }
            }
            binding.tvCount.text = card.count.toString()
            binding.root.setOnClickListener { onOpen(card) }
        }

        fun clear() {
            clearFace(binding.ivThumb1)
            clearFace(binding.ivThumb2)
            clearFace(binding.ivThumb3)
        }

        private fun load(view: ImageView, uid: Long?) {
            if (uid == null) {
                clearFace(view)
                view.setImageDrawable(null)
                return
            }
            Glide.with(view).load(MediaUris.ofUid(uid)).centerCrop().override(THUMB_PX).dontAnimate()
                .placeholder(R.color.language_check_off).into(view)
        }
    }

    private companion object {
        const val THUMB_PX = 160

        val DIFF = object : DiffUtil.ItemCallback<SuggestionCard>() {
            override fun areItemsTheSame(old: SuggestionCard, new: SuggestionCard) = old.kind == new.kind
            override fun areContentsTheSame(old: SuggestionCard, new: SuggestionCard) = old == new
        }
    }
}
