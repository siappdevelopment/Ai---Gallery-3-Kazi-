package com.example.thirdgallery.adapter

import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.thirdgallery.R
import com.example.thirdgallery.database.MonthRow
import com.example.thirdgallery.database.YearRow
import com.example.thirdgallery.databinding.ItemMonthCardBinding
import com.example.thirdgallery.databinding.ItemQuickChipBinding
import com.example.thirdgallery.databinding.ItemTimelineHeaderBinding
import com.example.thirdgallery.databinding.ItemYearCardBinding
import com.example.thirdgallery.helper.HorizontalSpacingDecoration
import com.example.thirdgallery.helper.MediaFormat
import com.example.thirdgallery.helper.MediaUris
import com.example.thirdgallery.model.QuickChip
import com.example.thirdgallery.model.TimelineZoom

private fun loadCover(view: ImageView, uid: Long?, widthPx: Int, heightPx: Int) {
    if (uid == null) {
        Glide.with(view).clear(view)
        view.setImageDrawable(null)
        return
    }
    Glide.with(view).load(MediaUris.ofUid(uid)).override(widthPx, heightPx).centerCrop().dontAnimate().into(view)
}

/** Collection chips (albums, videos, favorites) at the top of the Timeline. */
class QuickChipAdapter : ListAdapter<QuickChip, QuickChipAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemQuickChipBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class ViewHolder(private val binding: ItemQuickChipBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(chip: QuickChip) {
            binding.ivChipIcon.setImageResource(chip.icon)
            binding.tvChipLabel.text = chip.label
            binding.tvChipCount.text = chip.count.toString()
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<QuickChip>() {
            override fun areItemsTheSame(old: QuickChip, new: QuickChip) = old.label == new.label
            override fun areContentsTheSame(old: QuickChip, new: QuickChip) = old == new
        }
    }
}

/**
 * Single full-width row above the Timeline content: collection chips, the Years / Months / Days
 * zoom tabs and (Days only) the column zoom buttons.
 */
class TimelineHeaderAdapter(
    private val onZoomSelected: (TimelineZoom) -> Unit,
    private val onZoomIn: () -> Unit,
    private val onZoomOut: () -> Unit,
) : RecyclerView.Adapter<TimelineHeaderAdapter.ViewHolder>() {

    private var visible = false
    private var zoom = TimelineZoom.DAYS
    private var chips: List<QuickChip> = emptyList()
    private var canZoomIn = true
    private var canZoomOut = true

    fun setVisible(show: Boolean) {
        if (show == visible) return
        visible = show
        if (show) notifyItemInserted(0) else notifyItemRemoved(0)
    }

    fun setState(zoom: TimelineZoom, chips: List<QuickChip>, canZoomIn: Boolean, canZoomOut: Boolean) {
        if (zoom == this.zoom && chips == this.chips && canZoomIn == this.canZoomIn && canZoomOut == this.canZoomOut) return
        this.zoom = zoom
        this.chips = chips
        this.canZoomIn = canZoomIn
        this.canZoomOut = canZoomOut
        if (visible) notifyItemChanged(0)
    }

    override fun getItemCount(): Int = if (visible) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemTimelineHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(zoom, chips, canZoomIn, canZoomOut)

    inner class ViewHolder(private val binding: ItemTimelineHeaderBinding) : RecyclerView.ViewHolder(binding.root) {

        private val chipAdapter = QuickChipAdapter()

        init {
            val density = binding.root.resources.displayMetrics.density
            binding.rvChips.adapter = chipAdapter
            binding.rvChips.itemAnimator = null
            binding.rvChips.addItemDecoration(HorizontalSpacingDecoration((8 * density).toInt()))
            binding.tabYears.setOnClickListener { onZoomSelected(TimelineZoom.YEARS) }
            binding.tabMonths.setOnClickListener { onZoomSelected(TimelineZoom.MONTHS) }
            binding.tabDays.setOnClickListener { onZoomSelected(TimelineZoom.DAYS) }
            binding.btnZoomIn.setOnClickListener { onZoomIn() }
            binding.btnZoomOut.setOnClickListener { onZoomOut() }
        }

        fun bind(zoom: TimelineZoom, chips: List<QuickChip>, canZoomIn: Boolean, canZoomOut: Boolean) {
            binding.rvChips.visibility = if (chips.isEmpty()) View.GONE else View.VISIBLE
            chipAdapter.submitList(chips)
            binding.tabYears.isSelected = zoom == TimelineZoom.YEARS
            binding.tabMonths.isSelected = zoom == TimelineZoom.MONTHS
            binding.tabDays.isSelected = zoom == TimelineZoom.DAYS
            binding.zoomButtons.visibility = if (zoom == TimelineZoom.DAYS) View.VISIBLE else View.GONE
            binding.btnZoomIn.alpha = if (canZoomIn) 1f else 0.35f
            binding.btnZoomOut.alpha = if (canZoomOut) 1f else 0.35f
        }
    }
}

/** Timeline "Months": one white card per month with a 4 x 2 photo collage. */
class MonthAdapter(
    private val onMonthClick: (MonthRow) -> Unit,
) : ListAdapter<MonthRow, MonthAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemMonthCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: ViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(private val binding: ItemMonthCardBinding) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onMonthClick(getItem(position))
            }
        }

        private val photos get() = listOf(binding.ivMonth0, binding.ivMonth1, binding.ivMonth2, binding.ivMonth3, binding.ivMonth4)

        fun bind(row: MonthRow) {
            val context = binding.root.context
            binding.tvMonthTitle.text = MediaFormat.monthTitle(context, row.monthKey)
            binding.tvMonthCount.text = context.getString(R.string.photo_items, row.total)

            val density = context.resources.displayMetrics.density
            val covers = listOf(row.cover0, row.cover1, row.cover2, row.cover3, row.cover4)
            photos.forEachIndexed { index, view ->
                // The big photo is half the card, the others a quarter of it.
                val w = ((if (index == 0) 170 else 85) * density).toInt()
                val h = ((if (index == 0) 150 else 74) * density).toInt()
                loadCover(view, covers[index], w, h)
            }
            // "+N" on the last tile when the month has more than the five shown items.
            val more = row.total - covers.size
            binding.tvMonthMore.visibility = if (more > 0) View.VISIBLE else View.GONE
            if (more > 0) binding.tvMonthMore.text = "+$more"
        }

        fun clear() {
            photos.forEach { Glide.with(it).clear(it) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<MonthRow>() {
            override fun areItemsTheSame(old: MonthRow, new: MonthRow) = old.monthKey == new.monthKey
            override fun areContentsTheSame(old: MonthRow, new: MonthRow) = old == new
        }
    }
}

/** Timeline "Years": two big cards per row with the year and item count. */
class YearAdapter(
    private val onYearClick: (YearRow) -> Unit,
) : ListAdapter<YearRow, YearAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemYearCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: ViewHolder) {
        Glide.with(holder.itemView).clear(holder.coverView)
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(private val binding: ItemYearCardBinding) : RecyclerView.ViewHolder(binding.root) {

        val coverView: ImageView get() = binding.ivYear

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onYearClick(getItem(position))
            }
        }

        fun bind(row: YearRow) {
            val context = binding.root.context
            val density = context.resources.displayMetrics.density
            loadCover(binding.ivYear, row.cover, (200 * density).toInt(), (210 * density).toInt())
            binding.tvYear.text = MediaFormat.yearTitle(context, row.year)
            binding.tvYearCount.text = context.getString(R.string.photo_items, row.total)
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<YearRow>() {
            override fun areItemsTheSame(old: YearRow, new: YearRow) = old.year == new.year
            override fun areContentsTheSame(old: YearRow, new: YearRow) = old == new
        }
    }
}

/** Gives the two-column year grid its 12dp outer margin while keeping 8dp between the cards. */
class YearGridDecoration(private val outerPx: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        if (parent.getChildViewHolder(view) !is YearAdapter.ViewHolder) return
        val lp = view.layoutParams as? GridLayoutManager.LayoutParams ?: return
        if (lp.spanIndex == 0) outRect.left = outerPx else outRect.right = outerPx
    }
}
