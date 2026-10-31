package com.example.thirdgallery.helper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.sqrt

/**
 * A compact, lighting-tolerant signature of a face, built without any model: the face is turned and scaled so the
 * two eyes sit at fixed places (so pose in the picture plane and distance no longer matter), reduced to a 16 x 16
 * gray picture, and described by three parts: its normalised brightness and its horizontal and vertical brightness
 * changes (edges, which hold up better under different lighting). Each part is scaled to length 1, and the whole is
 * stored as signed bytes (768 per face).
 *
 * This is a much weaker "fingerprint" than a trained face-recognition model: it tells apart faces of one
 * photo session well, and different lighting, expression or angle can still make the same person look different.
 * Grouping that uses it is therefore strict (it prefers to leave one person in several groups than to merge two
 * people). A face-recognition model could replace this class without changing the rest.
 */
object FaceDescriptor {

    private const val SIZE = 16
    private const val WORK = SIZE * 2
    const val LENGTH = SIZE * SIZE * 3

    // Where the eyes go in the aligned picture, as shares of its width / height.
    private const val EYE_Y = 0.38f
    private const val EYE_LEFT_X = 0.30f
    private const val EYE_RIGHT_X = 0.70f

    /** The descriptor of the face with the given eye centres (in [source] pixels), or null if it cannot be made. */
    fun of(source: Bitmap, eyeA: PointF, eyeB: PointF): ByteArray? {
        val left = if (eyeA.x <= eyeB.x) eyeA else eyeB
        val right = if (eyeA.x <= eyeB.x) eyeB else eyeA
        if (right.x - left.x < 4f && kotlin.math.abs(right.y - left.y) < 4f) return null
        var aligned: Bitmap? = null
        return try {
            val matrix = Matrix()
            val ok = matrix.setPolyToPoly(
                floatArrayOf(left.x, left.y, right.x, right.y), 0,
                floatArrayOf(EYE_LEFT_X * WORK, EYE_Y * WORK, EYE_RIGHT_X * WORK, EYE_Y * WORK), 0, 2
            )
            if (!ok) return null
            aligned = Bitmap.createBitmap(WORK, WORK, Bitmap.Config.ARGB_8888)
            Canvas(aligned).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            describe(aligned)
        } catch (e: Exception) {
            null
        } finally {
            aligned?.recycle()
        }
    }

    private fun describe(aligned: Bitmap): ByteArray? {
        val pixels = IntArray(WORK * WORK)
        aligned.getPixels(pixels, 0, WORK, 0, 0, WORK, WORK)
        // 16 x 16 gray: the average of each 2 x 2 block.
        val gray = FloatArray(SIZE * SIZE)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            var sum = 0f
            for (dy in 0..1) for (dx in 0..1) {
                val p = pixels[(y * 2 + dy) * WORK + x * 2 + dx]
                sum += (((p shr 16) and 0xFF) * 0.299f + ((p shr 8) and 0xFF) * 0.587f + (p and 0xFF) * 0.114f)
            }
            gray[y * SIZE + x] = sum / 4f
        }
        val brightness = normalised(gray) ?: return null
        val dx = FloatArray(SIZE * SIZE)
        val dy = FloatArray(SIZE * SIZE)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val l = gray[y * SIZE + (x - 1).coerceAtLeast(0)]
            val r = gray[y * SIZE + (x + 1).coerceAtMost(SIZE - 1)]
            val u = gray[(y - 1).coerceAtLeast(0) * SIZE + x]
            val d = gray[(y + 1).coerceAtMost(SIZE - 1) * SIZE + x]
            dx[y * SIZE + x] = r - l
            dy[y * SIZE + x] = d - u
        }
        val horizontal = normalised(dx) ?: return null
        val vertical = normalised(dy) ?: return null
        val out = ByteArray(LENGTH)
        var index = 0
        for (part in arrayOf(brightness, horizontal, vertical)) for (v in part) out[index++] = (v * 127f).toInt().coerceIn(-127, 127).toByte()
        return out
    }

    /** [values] with their average removed and scaled to length 1; null when flat (nothing to describe). */
    private fun normalised(values: FloatArray): FloatArray? {
        var mean = 0f
        for (v in values) mean += v
        mean /= values.size
        var sum = 0f
        val out = FloatArray(values.size) { values[it] - mean }
        for (v in out) sum += v * v
        val norm = sqrt(sum)
        if (norm < 1e-3f) return null
        for (i in out.indices) out[i] /= norm
        return out
    }

    /** The descriptor as floats (each part still of length about 1, so a dot product of two is the sum of three cosines). */
    fun toFloats(bytes: ByteArray): FloatArray? {
        if (bytes.size != LENGTH) return null
        return FloatArray(LENGTH) { bytes[it] / 127f }
    }
}
