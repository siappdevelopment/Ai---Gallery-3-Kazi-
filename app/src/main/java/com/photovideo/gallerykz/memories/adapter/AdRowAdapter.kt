package com.photovideo.gallerykz.memories.adapter

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView

/**
 * Puts one ad row inside the list of [inner] (a paged media grid): the row sits before the item [indexOfAd] says (an index of
 * [inner]; -1 = no row yet, for instance while too little is loaded) and only while [setEnabled] is true. The ad itself lives in
 * [container], one view for the whole screen, which is moved into the row when it is bound. All changes of [inner] are passed
 * on with their positions shifted around the row, so the grid never reloads.
 */
class AdRowAdapter(
    private val inner: RecyclerView.Adapter<RecyclerView.ViewHolder>,
    private val container: ViewGroup,
    private val indexOfAd: () -> Int,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var enabled = false

    /** While true the list shows no rows at all, whatever [inner] holds (see [setHidden]). */
    private var hidden = false

    /** True while the ad view lives somewhere else (a pinned slot): the row keeps its height ([dockedHeightPx]) and holds no view. */
    private var docked = false
    var dockedHeightPx = 0

    /** Position of the ad row in this adapter (= the index of the inner item it sits before); -1 = none. */
    private var adPos = -1

    private val observer = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() {
            if (hidden) return
            val want = if (enabled) indexOfAd() else -1
            adPos = if (want in 0..inner.itemCount) want else -1
            notifyDataSetChanged()
        }

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
            if (hidden) return
            when {
                adPos < 0 || positionStart + itemCount <= adPos -> notifyItemRangeChanged(positionStart, itemCount, payload)
                positionStart >= adPos -> notifyItemRangeChanged(positionStart + 1, itemCount, payload)
                else -> {
                    notifyItemRangeChanged(positionStart, adPos - positionStart, payload)
                    notifyItemRangeChanged(adPos + 1, positionStart + itemCount - adPos, payload)
                }
            }
            sync()
        }

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            if (hidden) return
            if (adPos in 0..positionStart) {
                // Inserted behind the row.
                notifyItemRangeInserted(positionStart + 1, itemCount)
            } else {
                if (adPos > positionStart) adPos += itemCount
                notifyItemRangeInserted(positionStart, itemCount)
            }
            sync()
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            if (hidden) return
            when {
                adPos < 0 -> notifyItemRangeRemoved(positionStart, itemCount)
                positionStart + itemCount <= adPos -> {
                    notifyItemRangeRemoved(positionStart, itemCount)
                    adPos -= itemCount
                }
                positionStart >= adPos -> notifyItemRangeRemoved(positionStart + 1, itemCount)
                else -> {
                    // The removal reaches over the row: start again.
                    adPos = -1
                    notifyDataSetChanged()
                }
            }
            sync()
        }

        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
            if (hidden) return
            adPos = -1
            notifyDataSetChanged()
            sync()
        }
    }

    init {
        inner.registerAdapterDataObserver(observer)
    }

    /** The row is there only while there is an ad (or its loading skeleton) to show. */
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        sync()
    }

    /**
     * Hides every row of this adapter at once (true), or shows them again (false). It takes effect in the same frame, unlike
     * changing the paged data underneath, which arrives a frame or more later. The gallery uses it while a filter tab is switching,
     * so the items of the old filter are gone the moment the tab changes and the new ones appear together when they are loaded.
     */
    fun setHidden(value: Boolean) {
        if (hidden == value) return
        if (value) {
            val before = itemCount
            hidden = true
            adPos = -1
            if (before > 0) notifyItemRangeRemoved(0, before)
        } else {
            hidden = false
            adPos = -1
            notifyDataSetChanged()
            sync()
        }
    }

    /** The place of the row may have changed (the number of columns, a layout switch): moves it. */
    fun sync() {
        if (hidden) return
        val want = if (enabled) indexOfAd() else -1
        if (want == adPos) return
        val old = adPos
        if (old >= 0) {
            adPos = -1
            notifyItemRemoved(old)
        }
        if (want in 0..inner.itemCount) {
            adPos = want
            notifyItemInserted(want)
        }
    }

    /** The ad moved to the pinned slot (true) or back into its row (false); the row is rebound. */
    fun setDocked(value: Boolean) {
        if (docked == value) return
        docked = value
        if (adPos >= 0) notifyItemChanged(adPos)
    }

    /** Position of the ad row in this adapter; -1 = none. */
    fun adPosition(): Int = adPos

    /** True when [position] (of this adapter) is the ad row. */
    fun isAd(position: Int): Boolean = position == adPos

    /** The index in the inner adapter of the item at [position] of this adapter. */
    fun innerIndex(position: Int): Int = if (adPos in 0 until position) position - 1 else position

    override fun getItemCount(): Int = if (hidden) 0 else inner.itemCount + if (adPos >= 0) 1 else 0

    override fun getItemViewType(position: Int): Int =
        if (position == adPos) TYPE_AD else inner.getItemViewType(innerIndex(position))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType != TYPE_AD) return inner.onCreateViewHolder(parent, viewType)
        val host = FrameLayout(parent.context)
        host.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val pad = (6 * parent.resources.displayMetrics.density).toInt()
        host.setPadding(pad, pad, pad, pad)
        return AdHolder(host)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is AdHolder) attach(holder) else inner.onBindViewHolder(holder, innerIndex(position))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (holder is AdHolder) attach(holder) else inner.onBindViewHolder(holder, innerIndex(position), payloads)
    }

    private fun attach(holder: AdHolder) {
        val host = holder.itemView as ViewGroup
        if (docked) {
            // The ad is in the pinned slot: the row stays as tall as it was, so the grid does not jump.
            if (container.parent === host) host.removeView(container)
            host.minimumHeight = dockedHeightPx
            return
        }
        host.minimumHeight = 0
        if (container.parent === host) return
        (container.parent as? ViewGroup)?.removeView(container)
        host.addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is AdHolder) {
            val host = holder.itemView as ViewGroup
            if (container.parent === host) host.removeView(container)
        } else {
            inner.onViewRecycled(holder)
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        if (holder !is AdHolder) inner.onViewAttachedToWindow(holder)
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        if (holder !is AdHolder) inner.onViewDetachedFromWindow(holder)
    }

    override fun onFailedToRecycleView(holder: RecyclerView.ViewHolder): Boolean =
        if (holder is AdHolder) false else inner.onFailedToRecycleView(holder)

    private class AdHolder(view: FrameLayout) : RecyclerView.ViewHolder(view)

    private companion object {
        const val TYPE_AD = 1_000_000
    }
}
