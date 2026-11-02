package com.example.thirdgallery.adapter

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.ItemBurstCardBinding
import com.example.thirdgallery.databinding.ItemBurstFrameBinding
import com.example.thirdgallery.databinding.ItemBurstsHeaderBinding
import com.example.thirdgallery.helper.HorizontalSpacingDecoration
import com.example.thirdgallery.helper.MediaUris
import com.example.thirdgallery.viewmodel.BurstCardUi

/** Back, "Best from bursts" and the hint under it. */
class BurstsHeaderAdapter(private val onBack: () -> Unit) : RecyclerView.Adapter<BurstsHeaderAdapter.ViewHolder>() {

    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemBurstsHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.binding.btnBack.setOnClickListener { onBack() }
    }

    class ViewHolder(val binding: ItemBurstsHeaderBinding) : RecyclerView.ViewHolder(binding.root)
}

/** One card per burst: date and shots, the selected shot large, the strip of all shots, Keep selected, heart and album. */
class BurstCardAdapter(
    private val onSelect: (key: Long, uid: Long) -> Unit,
    private val onKeep: (key: Long) -> Unit,
    private val onFavorite: (key: Long) -> Unit,
    private val onAddToAlbum: (key: Long) -> Unit,
) : ListAdapter<BurstCardUi, BurstCardAdapter.ViewHolder>(DIFF) {

    // One pool for every strip, so frames scrolled off in one card are reused by the next.
    private val framePool = RecyclerView.RecycledViewPool()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).key

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemBurstCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: ViewHolder) {
        clearFace(holder.binding.ivBig)
        holder.resetShown()
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(val binding: ItemBurstCardBinding) : RecyclerView.ViewHolder(binding.root) {

        private val frames = BurstFrameAdapter { uid -> boundKey?.let { onSelect(it, uid) } }
        private var boundKey: Long? = null
        private var shownBig = 0L

        init {
            binding.rvFrames.layoutManager = LinearLayoutManager(binding.root.context, LinearLayoutManager.HORIZONTAL, false)
            binding.rvFrames.adapter = frames
            binding.rvFrames.itemAnimator = null
            binding.rvFrames.setRecycledViewPool(framePool)
            binding.rvFrames.addItemDecoration(HorizontalSpacingDecoration((6 * binding.root.resources.displayMetrics.density).toInt()))
            binding.btnKeep.setOnClickListener { boundKey?.let(onKeep) }
            binding.btnFavorite.setOnClickListener { boundKey?.let(onFavorite) }
            binding.btnAddToAlbum.setOnClickListener { boundKey?.let(onAddToAlbum) }
        }

        /** The big picture was cleared with the recycling: it must be loaded again when the holder is reused. */
        fun resetShown() {
            shownBig = 0L
        }

        fun bind(card: BurstCardUi) {
            boundKey = card.key
            val context = binding.root.context
            binding.tvDate.text = DateUtils.formatDateTime(
                context, card.dateMs, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR,
            )
            binding.tvShots.text = context.getString(R.string.burst_shots, card.uids.size)
            if (shownBig != card.selectedUid) {
                shownBig = card.selectedUid
                Glide.with(binding.ivBig)
                    .load(MediaUris.ofUid(card.selectedUid))
                    .centerCrop()
                    .override(BIG_PX)
                    .dontAnimate()
                    .placeholder(R.color.language_check_off)
                    .into(binding.ivBig)
            }
            frames.submit(card.uids, card.selectedUid, card.bestUid, card.done)
            binding.actionsRow.visibility = if (card.done) View.GONE else View.VISIBLE
            binding.doneRow.visibility = if (card.done) View.VISIBLE else View.GONE
        }
    }

    private companion object {
        const val BIG_PX = 900

        val DIFF = object : DiffUtil.ItemCallback<BurstCardUi>() {
            override fun areItemsTheSame(old: BurstCardUi, new: BurstCardUi) = old.key == new.key
            override fun areContentsTheSame(old: BurstCardUi, new: BurstCardUi) = old == new
        }
    }
}

/** The strip of a burst: 40dp frames; the selected one is bright, the recommended one carries the pink star. */
class BurstFrameAdapter(
    private val onPick: (Long) -> Unit,
) : RecyclerView.Adapter<BurstFrameAdapter.ViewHolder>() {

    private var uids: List<Long> = emptyList()
    private var selected = 0L
    private var best = 0L
    private var locked = false

    fun submit(uids: List<Long>, selected: Long, best: Long, locked: Boolean) {
        val listChanged = uids != this.uids
        val old = this.selected
        this.uids = uids
        this.selected = selected
        this.best = best
        this.locked = locked
        if (listChanged) {
            notifyDataSetChanged()
        } else {
            // Only the frames whose look changed are redrawn.
            uids.forEachIndexed { index, uid -> if (uid == old || uid == selected) notifyItemChanged(index) }
        }
    }

    override fun getItemCount() = uids.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemBurstFrameBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val uid = uids[position]
        val b = holder.binding
        Glide.with(b.ivFrame)
            .load(MediaUris.ofUid(uid))
            .centerCrop()
            .override(FRAME_PX)
            .dontAnimate()
            .placeholder(R.color.language_check_off)
            .into(b.ivFrame)
        b.ivFrame.alpha = if (uid == selected) 1f else 0.5f
        b.ivStar.visibility = if (uid == best) View.VISIBLE else View.GONE
        b.root.setOnClickListener { if (!locked) onPick(uid) }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        clearFace(holder.binding.ivFrame)
        super.onViewRecycled(holder)
    }

    class ViewHolder(val binding: ItemBurstFrameBinding) : RecyclerView.ViewHolder(binding.root)

    private companion object {
        const val FRAME_PX = 120
    }
}
