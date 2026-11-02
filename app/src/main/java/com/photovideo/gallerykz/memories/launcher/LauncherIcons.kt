package com.photovideo.gallerykz.memories.launcher

import android.graphics.Outline
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView

/**
 * Shows app icons as rounded squares everywhere (home, folders, dock, drawer). Adaptive icons are drawn from their
 * background and foreground layers, so the phone's own mask (circle, squircle...) never changes the shape; legacy icons
 * keep their own artwork and aspect ratio.
 */
object LauncherIcons {

    /** Corner radius as a share of the icon size (a 50dp icon gets 20dp corners). */
    const val CORNER_RATIO = 0.4f

    private val outline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, view.width * CORNER_RATIO)
        }
    }

    /** Puts [icon] into [view] (which is [sizePx] wide and high). */
    fun bind(view: ImageView, icon: Drawable, sizePx: Int) {
        val layered = adaptiveLayers(icon, sizePx)
        if (layered != null) {
            view.setImageDrawable(layered)
            view.outlineProvider = outline
            view.clipToOutline = true
        } else {
            view.clipToOutline = false
            view.setImageDrawable(icon)
        }
    }

    private fun adaptiveLayers(icon: Drawable, sizePx: Int): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || icon !is AdaptiveIconDrawable) return null
        // A private copy, so the bounds of the shared drawable are never touched.
        val copy = icon.constantState?.newDrawable()?.mutate() as? AdaptiveIconDrawable ?: return null
        val background = copy.background ?: return null
        val foreground = copy.foreground ?: return null
        val layers = LayerDrawable(arrayOf(background, foreground))
        // The layers are 108dp and only the middle 72dp show: grow them by half so the visible part fills the view.
        val grow = -(sizePx / 4)
        layers.setLayerInset(0, grow, grow, grow, grow)
        layers.setLayerInset(1, grow, grow, grow, grow)
        return layers
    }
}
