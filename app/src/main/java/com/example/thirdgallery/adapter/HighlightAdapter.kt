package com.example.thirdgallery.adapter

import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.ItemHighlightBinding
import com.example.thirdgallery.helper.MediaUris
import com.example.thirdgallery.model.HighlightCard
import com.example.thirdgallery.model.HighlightKind
import java.util.Calendar

/** The Highlights cards of For you: a three-photo collage, the big title and "period · N photos". */
class HighlightAdapter : ListAdapter<HighlightCard, HighlightAdapter.ViewHolder>(DIFF) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemHighlightBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: ViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    class ViewHolder(private val binding: ItemHighlightBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(card: HighlightCard) {
            val context = binding.root.context
            load(binding.ivBig, card.uids.getOrNull(0), BIG_PX)
            load(binding.ivTop, card.uids.getOrNull(1), SMALL_PX)
            load(binding.ivBottom, card.uids.getOrNull(2), SMALL_PX)
            binding.tvMonth.text = title(context, card)
            val photos = context.resources.getQuantityString(R.plurals.intro2_photos, card.count, card.count)
            val period = period(context, card)
            binding.tvMeta.text = if (period.isEmpty()) photos else context.getString(R.string.viewer_meta, period, photos)
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

        private fun title(context: Context, card: HighlightCard): String = when (card.kind) {
            HighlightKind.RECENT -> context.getString(R.string.highlight_recent)
            HighlightKind.LAST_WEEK -> context.getString(R.string.highlight_last_week)
            HighlightKind.LAST_MONTH -> context.getString(R.string.highlight_last_month)
            HighlightKind.LAST_YEAR -> context.getString(R.string.highlight_last_year)
            HighlightKind.BEST_MONTH -> context.getString(
                R.string.highlight_best_of,
                DateUtils.formatDateTime(context, card.fromMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_MONTH_DAY or DateUtils.FORMAT_NO_YEAR),
            )
            HighlightKind.BEST_YEAR -> context.getString(R.string.highlight_best_of, year(card.fromMs).toString())
            HighlightKind.BEST_DATE -> context.getString(
                R.string.highlight_best_of,
                DateUtils.formatDateTime(context, card.fromMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_MONTH),
            )
        }

        /** What the card covers, under the title; empty when the title already says it. */
        private fun period(context: Context, card: HighlightCard): String = when (card.kind) {
            HighlightKind.RECENT -> context.getString(R.string.highlight_last_24h)
            HighlightKind.LAST_WEEK -> DateUtils.formatDateRange(
                context,
                card.fromMs,
                card.toMs - 1,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
            )
            HighlightKind.LAST_MONTH -> DateUtils.formatDateTime(
                context,
                card.fromMs,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_MONTH_DAY or DateUtils.FORMAT_SHOW_YEAR,
            )
            HighlightKind.LAST_YEAR -> year(card.fromMs).toString()
            HighlightKind.BEST_MONTH, HighlightKind.BEST_DATE -> year(card.fromMs).toString()
            HighlightKind.BEST_YEAR -> ""
        }

        private fun year(ms: Long): Int = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.YEAR)
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
