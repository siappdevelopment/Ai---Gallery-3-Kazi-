package com.example.thirdgallery.helper

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.thirdgallery.model.Adjustment
import com.example.thirdgallery.model.CropBox
import com.example.thirdgallery.model.EditFilter
import com.example.thirdgallery.model.EditState
import com.example.thirdgallery.model.Stroke
import java.io.IOException
import kotlin.math.max

/**
 * The picture maths of the photo editor, shared by the live preview and the saved copy so both look the same: the colour
 * adjustments and filters as one colour matrix, rotate / flip, crop to the chosen shape, brush strokes and text.
 */
object EditRenderer {

    /** Text size as a share of the picture's width (the design's 28 on a 290 wide picture). */
    const val TEXT_SHARE = 0.096f

    /** The widest a line of text gets, as a share of the picture's width. */
    private const val TEXT_WIDTH_SHARE = 0.92f

    // ---- colour ----

    /**
     * Adjustments first (brightness, contrast, saturation; Exposure, Highlights, Shadows and Sharpness feed those three
     * the way the design's filter does), then the chosen filter.
     */
    fun colorMatrix(state: EditState): ColorMatrix {
        val v = { a: Adjustment -> state.value(a).toFloat() }
        val brightness = 1f + (v(Adjustment.BRIGHTNESS) + v(Adjustment.EXPOSURE) * 0.7f + v(Adjustment.HIGHLIGHTS) * 0.2f) / 200f
        val contrast = 1f + (v(Adjustment.CONTRAST) + v(Adjustment.SHARPNESS) * 0.3f - v(Adjustment.SHADOWS) * 0.2f) / 200f
        val saturation = 1f + v(Adjustment.SATURATION) / 100f
        return ColorMatrix().apply {
            postConcat(brightnessMatrix(brightness))
            postConcat(contrastMatrix(contrast))
            postConcat(ColorMatrix().apply { setSaturation(saturation.coerceAtLeast(0f)) })
            filterMatrix(state.filter)?.let { postConcat(it) }
        }
    }

    /** The matrix of a filter alone (for the small previews of the Filters tool); null for Original. */
    fun filterMatrix(filter: EditFilter): ColorMatrix? = when (filter) {
        EditFilter.ORIGINAL -> null
        EditFilter.VIVID -> ColorMatrix().apply {
            postConcat(ColorMatrix().apply { setSaturation(1.6f) })
            postConcat(contrastMatrix(1.1f))
        }
        EditFilter.WARM -> ColorMatrix().apply {
            postConcat(ColorMatrix().apply { setSaturation(1.3f) })
            postConcat(ColorMatrix(floatArrayOf(1.1f, 0f, 0f, 0f, 8f, 0f, 1.0f, 0f, 0f, 3f, 0f, 0f, 0.86f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        }
        EditFilter.COOL -> ColorMatrix().apply {
            postConcat(ColorMatrix().apply { setSaturation(0.9f) })
            postConcat(ColorMatrix(floatArrayOf(0.92f, 0f, 0f, 0f, 0f, 0f, 1.0f, 0f, 0f, 0f, 0f, 0f, 1.12f, 0f, 6f, 0f, 0f, 0f, 1f, 0f)))
            postConcat(brightnessMatrix(1.05f))
        }
        EditFilter.MONO -> ColorMatrix().apply {
            postConcat(ColorMatrix().apply { setSaturation(0f) })
            postConcat(contrastMatrix(1.1f))
        }
        EditFilter.FADE -> ColorMatrix().apply {
            postConcat(contrastMatrix(0.8f))
            postConcat(brightnessMatrix(1.12f))
            postConcat(ColorMatrix().apply { setSaturation(0.8f) })
        }
    }

    private fun brightnessMatrix(b: Float) =
        ColorMatrix(floatArrayOf(b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f))

    private fun contrastMatrix(c: Float): ColorMatrix {
        val t = 127.5f * (1f - c)
        return ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f))
    }

    // ---- shape ----

    /** The picture turned by [rotation] degrees (clockwise) and mirrored when [flipped]. */
    fun transform(source: Bitmap, rotation: Int, flipped: Boolean): Bitmap {
        if (rotation % 360 == 0 && !flipped) return source
        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            if (flipped) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /** The biggest box of width / height [ratio] that fits a [width] x [height] picture, in its middle. */
    fun boxForRatio(ratio: Float, width: Int, height: Int): CropBox {
        val imageAspect = width.toFloat() / height.coerceAtLeast(1)
        var w = 1f
        var h = 1f
        if (ratio > imageAspect) h = imageAspect / ratio else w = ratio / imageAspect
        return CropBox((1f - w) / 2f, (1f - h) / 2f, (1f + w) / 2f, (1f + h) / 2f)
    }

    // ---- decoding ----

    /** Decodes [uri] so its long side is at most [maxSide]; null when it cannot be read. */
    fun decode(resolver: ContentResolver, uri: Uri, maxSide: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longSide = max(info.size.width, info.size.height)
                if (longSide > maxSide) decoder.setTargetSampleSize(Integer.highestOneBit((longSide / maxSide).coerceAtLeast(1)))
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) {
                null
            } else {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = Integer.highestOneBit((max(bounds.outWidth, bounds.outHeight) / maxSide).coerceAtLeast(1))
                }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            }
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    // ---- the saved picture ----

    /**
     * The edited picture at full resolution (up to [maxSide]): turned, cropped to the crop box, with the colour matrix
     * applied, then the strokes and the text drawn on it. Null when the original cannot be read.
     */
    fun render(context: Context, uri: Uri, state: EditState, maxSide: Int): Bitmap? {
        val source = decode(context.contentResolver, uri, maxSide) ?: return null
        val turned = transform(source, state.rotation, state.flipped)
        val tw = turned.width
        val th = turned.height
        val box = state.crop
        val left = (box.left * tw).toInt().coerceIn(0, tw - 1)
        val top = (box.top * th).toInt().coerceIn(0, th - 1)
        val cropW = ((box.right * tw).toInt() - left).coerceIn(1, tw - left)
        val cropH = ((box.bottom * th).toInt() - top).coerceIn(1, th - top)
        val out = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(colorMatrix(state)) }
        canvas.drawBitmap(turned, -left.toFloat(), -top.toFloat(), paint)

        canvas.translate(-left.toFloat(), -top.toFloat())
        drawStrokes(canvas, state.strokes, tw.toFloat(), th.toFloat())
        drawText(canvas, state.text, state.color, state.textX * tw, state.textY * th, tw.toFloat())
        if (turned !== source) turned.recycle()
        source.recycle()
        return out
    }

    /** Draws [strokes] on a picture of [width] x [height] pixels (the live preview draws them the same way). */
    fun drawStrokes(canvas: Canvas, strokes: List<Stroke>, width: Float, height: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (stroke in strokes) {
            val points = stroke.points
            if (points.isEmpty()) continue
            paint.color = stroke.color
            paint.strokeWidth = stroke.size * width
            if (points.size == 1) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(points[0].x * width, points[0].y * height, stroke.size * width / 2f, paint)
                paint.style = Paint.Style.STROKE
                continue
            }
            val path = Path().apply {
                moveTo(points[0].x * width, points[0].y * height)
                for (i in 1 until points.size) lineTo(points[i].x * width, points[i].y * height)
            }
            canvas.drawPath(path, paint)
        }
    }

    /** Draws [text] centred on ([centerX], [centerY]) on a picture [width] pixels wide. */
    fun drawText(canvas: Canvas, text: String, color: Int, centerX: Float, centerY: Float, width: Float) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = width * TEXT_SHARE
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(width * 0.012f, 0f, width * 0.006f, 0x40000000)
        }
        val layout = StaticLayout.Builder.obtain(trimmed, 0, trimmed.length, paint, (width * TEXT_WIDTH_SHARE).toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        canvas.save()
        canvas.translate(centerX - layout.width / 2f, centerY - layout.height / 2f)
        layout.draw(canvas)
        canvas.restore()
    }
}
