package com.photovideo.gallerykz.memories.launcher

/**
 * The maths behind equal-looking app icons. App icons come in very different shapes: a full tile, a logo on nothing (Chrome, Maps,
 * Play Store), art with an empty margin of 5 to 20 percent. Drawn in the same box they look big or small. The launcher measures the
 * visible part of each icon and scales it so the visible part, not the empty margin, fills the box.
 */
object IconBounds {

    /** Pixels with an alpha below this count as empty (soft shadows and anti-aliasing stay out of the measurement). */
    const val ALPHA_MIN = 24

    /** Icons that fill at least this share of the box on both sides are left as they are (tiles). */
    const val FULL_SHARE = 0.94f

    /** The most an icon is enlarged: a tiny logo is never blown up beyond this. */
    const val MAX_SCALE = 1.3f

    /** How far (in each colour channel) a pixel may differ from the ignored colour and still count as that colour. */
    const val COLOUR_TOLERANCE = 40

    /**
     * The box that contains every visible pixel of the [w] x [h] picture (ARGB [pixels]), as left, top, right and bottom with right and
     * bottom exclusive; null when nothing is visible. With [ignoreColour] (the colour of a plain tile behind a logo) pixels of that colour
     * count as empty too, so a logo on a full white layer is measured by the logo alone.
     */
    fun contentBox(pixels: IntArray, w: Int, h: Int, alphaMin: Int = ALPHA_MIN, ignoreColour: Int? = null): IntArray? {
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val pixel = pixels[row + x]
                if ((pixel ushr 24) < alphaMin) continue
                if (ignoreColour != null && isNear(pixel, ignoreColour)) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return null
        return intArrayOf(left, top, right + 1, bottom + 1)
    }

    private fun isNear(a: Int, b: Int): Boolean =
        kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= COLOUR_TOLERANCE &&
            kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= COLOUR_TOLERANCE &&
            kotlin.math.abs((a and 0xFF) - (b and 0xFF)) <= COLOUR_TOLERANCE

    /** True when the visible part already fills the box, so the icon needs no change. */
    fun fillsBox(box: IntArray, w: Int, h: Int): Boolean =
        (box[2] - box[0]) >= w * FULL_SHARE && (box[3] - box[1]) >= h * FULL_SHARE

    /** How much to scale a [boxW] x [boxH] visible part so that its longer side fills [size]; never above [MAX_SCALE], never below 1. */
    fun scaleFor(boxW: Int, boxH: Int, size: Int, maxScale: Float = MAX_SCALE): Float {
        if (boxW <= 0 || boxH <= 0 || size <= 0) return 1f
        val fit = size.toFloat() / maxOf(boxW, boxH)
        return fit.coerceIn(1f, maxScale)
    }
}
