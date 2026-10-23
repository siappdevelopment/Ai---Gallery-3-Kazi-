package com.example.thirdgallery.adapter

import android.view.View
import com.example.thirdgallery.R

/** Payload that redraws only the selection marks of the visible tiles. */
const val PAYLOAD_SELECTION = "selection"

/** What a grid adapter needs to know about the multi-selection of its screen. */
interface MediaSelection {
    val active: Boolean
    fun isSelected(uid: Long): Boolean

    /** Long press on a tile: selection mode on with this item chosen. */
    fun onLongPress(uid: Long)

    /** Tap on a tile while selecting. */
    fun onToggle(uid: Long)

    companion object {
        /** No selection (screens that do not support it). */
        val NONE = object : MediaSelection {
            override val active = false
            override fun isSelected(uid: Long) = false
            override fun onLongPress(uid: Long) = Unit
            override fun onToggle(uid: Long) = Unit
        }
    }
}

/** Draws the check circle (pink + tick when chosen, dim when not) and shrinks a chosen tile (design: .88). */
fun drawSelection(check: android.widget.ImageView, tile: View, selecting: Boolean, selected: Boolean) {
    check.visibility = if (selecting) View.VISIBLE else View.GONE
    check.setBackgroundResource(if (selected) R.drawable.bg_selection_check_on else R.drawable.bg_selection_check_off)
    if (selected) check.setImageResource(R.drawable.ic_check) else check.setImageDrawable(null)
    val scale = if (selected) 0.88f else 1f
    tile.scaleX = scale
    tile.scaleY = scale
}
