package com.example.thirdgallery.helper

import android.graphics.Bitmap
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

class FaceCropTransformation(
    private val left: Float,
    private val top: Float,
    private val right: Float,
    private val bottom: Float
) : BitmapTransformation() {

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val width = toTransform.width
        val height = toTransform.height
        val side = min(min(width, height), (max((right - left) * width, (bottom - top) * height) * MARGIN).toInt())
        if (side <= 0) return toTransform
        val x = (((left + right) / 2f) * width - side / 2f).toInt().coerceIn(0, width - side)
        val y = (((top + bottom) / 2f) * height - side / 2f).toInt().coerceIn(0, height - side)
        return Bitmap.createBitmap(toTransform, x, y, side, side)
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("face_crop_$left$top$right$bottom".toByteArray(CHARSET))
    }

    override fun equals(other: Any?) = other is FaceCropTransformation &&
        other.left == left && other.top == top && other.right == right && other.bottom == bottom

    override fun hashCode() = listOf(left, top, right, bottom).hashCode()

    private companion object {
        const val MARGIN = 1.6f
    }
}
