package com.photovideo.gallerykz.memories.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import java.util.WeakHashMap

/**
 * Shows app icons as rounded squares everywhere (home, folders, dock, drawer). Adaptive icons are drawn from their
 * background and foreground layers, so the phone's own mask (circle, squircle...) never changes the shape.
 *
 * Icons are also made to look equally big. Their art differs a lot: a Chrome ring fills the whole safe area of an adaptive icon, a Maps
 * pin fills less than half of it, and the icons some phones (Vivo among them) supply have an empty margin of their own. So the visible art
 * of every icon is measured and enlarged, within limits, until it fills the same area; icons that already fill it are left as they are.
 */
object LauncherIcons {

    /** Corner radius as a share of the icon size (a 50dp icon gets 20dp corners). */
    const val CORNER_RATIO = 0.4f

    /** The art of an adaptive icon is brought to about this size in dp (of the 72dp that show): big, but never touching the edge of the tile. */
    private const val SAFE_AREA_DP = 56f
    private const val LAYER_DP = 108f
    private const val WINDOW_DP = 72f

    /** The most the art of an adaptive icon is enlarged. */
    private const val MAX_ART_SCALE = 2.0f

    /** The art is always measured on a layer of this size, so tiny folder previews are measured as exactly as big icons. */
    private const val MEASURE_PX = 216

    private val outline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, view.width * CORNER_RATIO)
        }
    }

    /** The evened-out picture of an icon at one size; [bitmap] is null when the icon needs no change. [rounded]: clip it to a rounded square. */
    private class Evened(val bitmap: Bitmap?, val rounded: Boolean)

    private val cache = WeakHashMap<Drawable, HashMap<Int, Evened>>()

    /** Puts [icon] into [view] (which is [sizePx] wide and high). */
    fun bind(view: ImageView, icon: Drawable, sizePx: Int) {
        val evened = evened(icon, sizePx)
        if (evened?.bitmap != null) {
            view.setImageDrawable(BitmapDrawable(view.resources, evened.bitmap))
            if (evened.rounded) {
                view.outlineProvider = outline
                view.clipToOutline = true
            } else {
                view.clipToOutline = false
            }
            return
        }
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

    private fun evened(icon: Drawable, sizePx: Int): Evened? {
        if (sizePx < MIN_MEASURE_PX) return null
        val bySize = cache.getOrPut(icon) { HashMap() }
        bySize[sizePx]?.let { return it }
        val result = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && icon is AdaptiveIconDrawable) renderAdaptive(icon, sizePx) else renderPlain(icon, sizePx)
        } catch (e: Exception) {
            Evened(null, false)
        }
        bySize[sizePx] = result
        return result
    }

    /**
     * An adaptive icon: the background stays, and the foreground (the logo) is enlarged around the centre until its art reaches the safe area.
     * Tiles whose art already fills it keep the plain two-layer drawing.
     */
    private fun renderAdaptive(icon: AdaptiveIconDrawable, sizePx: Int): Evened {
        val copy = icon.constantState?.newDrawable()?.mutate() as? AdaptiveIconDrawable ?: return Evened(null, false)
        val foreground = copy.foreground ?: return Evened(null, false)
        val layerPx = (sizePx * LAYER_DP / WINDOW_DP).toInt()
        val layer = Bitmap.createBitmap(MEASURE_PX, MEASURE_PX, Bitmap.Config.ARGB_8888)
        foreground.setBounds(0, 0, MEASURE_PX, MEASURE_PX)
        foreground.draw(Canvas(layer))
        val pixels = IntArray(MEASURE_PX * MEASURE_PX)
        layer.getPixels(pixels, 0, MEASURE_PX, 0, 0, MEASURE_PX, MEASURE_PX)
        layer.recycle()
        // A layer with an opaque corner is a plain tile with the logo on it (Play Store): the tile colour does not count as art.
        val corner = pixels[0]
        val tile = if ((corner ushr 24) >= 200) corner else null
        val box = IconBounds.contentBox(pixels, MEASURE_PX, MEASURE_PX, ignoreColour = tile) ?: return Evened(null, false)
        val artDp = maxOf(box[2] - box[0], box[3] - box[1]) * LAYER_DP / MEASURE_PX
        val scale = (SAFE_AREA_DP / artDp).coerceIn(1f, MAX_ART_SCALE)
        if (scale < MIN_CHANGE) return Evened(null, false)

        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val inset = (layerPx - sizePx) / 2
        copy.background?.let {
            it.setBounds(-inset, -inset, sizePx + inset, sizePx + inset)
            it.draw(canvas)
        }
        canvas.save()
        canvas.scale(scale, scale, sizePx / 2f, sizePx / 2f)
        foreground.setBounds(-inset, -inset, sizePx + inset, sizePx + inset)
        foreground.draw(canvas)
        canvas.restore()
        return Evened(out, true)
    }

    /** An icon that is not adaptive: its visible part is cut out of its empty margin and scaled to fill the box (never above the limit). */
    private fun renderPlain(icon: Drawable, sizePx: Int): Evened {
        val picture = icon.constantState?.newDrawable()?.mutate() ?: icon
        val full = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        picture.setBounds(0, 0, sizePx, sizePx)
        picture.draw(Canvas(full))
        val pixels = IntArray(sizePx * sizePx)
        full.getPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        val box = IconBounds.contentBox(pixels, sizePx, sizePx)
        if (box == null || IconBounds.fillsBox(box, sizePx, sizePx)) {
            full.recycle()
            return Evened(null, false)
        }
        val boxW = box[2] - box[0]
        val boxH = box[3] - box[1]
        val scale = IconBounds.scaleFor(boxW, boxH, sizePx)
        val outW = (boxW * scale).toInt().coerceAtMost(sizePx)
        val outH = (boxH * scale).toInt().coerceAtMost(sizePx)
        val result = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val left = (sizePx - outW) / 2
        val top = (sizePx - outH) / 2
        Canvas(result).drawBitmap(
            full,
            Rect(box[0], box[1], box[2], box[3]),
            Rect(left, top, left + outW, top + outH),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        full.recycle()
        return Evened(result, false)
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

    private const val MIN_MEASURE_PX = 24

    /** An enlargement below this is not worth redrawing the icon for. */
    private const val MIN_CHANGE = 1.04f
}
