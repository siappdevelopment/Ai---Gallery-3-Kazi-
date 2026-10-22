package com.example.thirdgallery.helper

import android.graphics.Rect
import android.view.View
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * Even gap between consecutive items of a horizontal list (none before the first item, so the
 * list's own start padding stays exact). Works for left-to-right and right-to-left layouts.
 */
class HorizontalSpacingDecoration(private val gapPx: Int) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION || position == 0) return
        if (ViewCompat.getLayoutDirection(parent) == ViewCompat.LAYOUT_DIRECTION_RTL) {
            outRect.right = gapPx
        } else {
            outRect.left = gapPx
        }
    }
}
