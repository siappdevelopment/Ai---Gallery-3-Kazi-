package com.example.thirdgallery.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.example.thirdgallery.helper.EditRenderer
import com.example.thirdgallery.model.Stroke
import com.example.thirdgallery.model.StrokePoint

/**
 * The picture of the photo editor (design: a 6dp rounded frame, at most 290dp wide, in the shape of the crop): the picture
 * itself with the colour matrix, the brush strokes on top, the text, and the thirds grid while cropping. The picture fills
 * the frame (a centred crop), strokes and text are positioned as shares of the frame so they survive any size.
 */
class EditCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val strokes = StrokeLayer(context)
    private val grid = GridLayer(context)
    private val text = TextView(context).apply {
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(10f, 0f, 3f, 0x40000000)
        includeFontPadding = false
    }

    private var aspect = 3f / 4f

    /** A brush stroke was finished by the finger. */
    var onStroke: ((Stroke) -> Unit)? = null

    init {
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(strokes, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(text, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, 6 * density)
        }
        clipToOutline = true
        strokes.onStroke = { onStroke?.invoke(it) }
    }

    fun setBitmap(bitmap: Bitmap?) = image.setImageBitmap(bitmap)

    fun setColorMatrix(matrix: ColorMatrix?) {
        image.colorFilter = matrix?.let { ColorMatrixColorFilter(it) }
    }

    /** Width / height of the frame. */
    fun setAspect(value: Float) {
        if (value <= 0f || value == aspect) return
        aspect = value
        requestLayout()
    }

    fun setStrokes(list: List<Stroke>) {
        strokes.strokes = list
        strokes.invalidate()
    }

    fun setText(value: String, color: Int) {
        text.text = value
        text.setTextColor(color)
        text.visibility = if (value.isBlank()) View.GONE else View.VISIBLE
    }

    fun setGridVisible(visible: Boolean) {
        grid.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Draw mode: a finger draws strokes of [color] and [sizeShare] (a share of the frame's width). */
    fun setBrush(enabled: Boolean, color: Int, sizeShare: Float) {
        strokes.drawing = enabled
        strokes.color = color
        strokes.sizeShare = sizeShare
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = minOf(MeasureSpec.getSize(widthMeasureSpec), (MAX_WIDTH_DP * density).toInt())
        val maxHeight = MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: Int.MAX_VALUE
        var w = maxWidth.toFloat()
        var h = w / aspect
        if (h > maxHeight) {
            h = maxHeight.toFloat()
            w = h * aspect
        }
        val wi = w.toInt().coerceAtLeast(1)
        val hi = h.toInt().coerceAtLeast(1)
        setMeasuredDimension(wi, hi)
        // The text is a share of the frame's width, the same share the saved picture uses.
        text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, wi * EditRenderer.TEXT_SHARE)
        text.setPadding((wi * 0.04f).toInt(), 0, (wi * 0.04f).toInt(), 0)
        text.measure(MeasureSpec.makeMeasureSpec(wi, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(hi, MeasureSpec.AT_MOST))
        image.measure(MeasureSpec.makeMeasureSpec(wi, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(hi, MeasureSpec.EXACTLY))
        strokes.measure(MeasureSpec.makeMeasureSpec(wi, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(hi, MeasureSpec.EXACTLY))
        grid.measure(MeasureSpec.makeMeasureSpec(wi, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(hi, MeasureSpec.EXACTLY))
    }

    /** Brush strokes drawn with the finger; the points are kept as shares of the frame. */
    private class StrokeLayer(context: Context) : View(context) {

        var strokes: List<Stroke> = emptyList()
        var drawing = false
        var color = 0
        var sizeShare = 0.03f
        var onStroke: ((Stroke) -> Unit)? = null

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private var current: MutableList<StrokePoint>? = null

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            strokes.forEach { drawStroke(canvas, it.color, it.size, it.points, w, h) }
            current?.let { drawStroke(canvas, color, sizeShare, it, w, h) }
        }

        private fun drawStroke(canvas: Canvas, strokeColor: Int, size: Float, points: List<StrokePoint>, w: Float, h: Float) {
            if (points.isEmpty()) return
            paint.color = strokeColor
            paint.strokeWidth = size * w
            if (points.size == 1) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(points[0].x * w, points[0].y * h, size * w / 2f, paint)
                paint.style = Paint.Style.STROKE
                return
            }
            val path = Path().apply {
                moveTo(points[0].x * w, points[0].y * h)
                for (i in 1 until points.size) lineTo(points[i].x * w, points[i].y * h)
            }
            canvas.drawPath(path, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!drawing || width == 0 || height == 0) return false
            val point = StrokePoint((event.x / width).coerceIn(0f, 1f), (event.y / height).coerceIn(0f, 1f))
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    current = mutableListOf(point)
                }
                MotionEvent.ACTION_MOVE -> current?.add(point)
                MotionEvent.ACTION_UP -> {
                    current?.let { onStroke?.invoke(Stroke(color, sizeShare, it.toList())) }
                    current = null
                }
                MotionEvent.ACTION_CANCEL -> current = null
            }
            invalidate()
            return true
        }
    }

    /** The white frame and thirds lines of the Crop tool. */
    private class GridLayer(context: Context) : View(context) {

        private val density = resources.displayMetrics.density
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFFFFFFF.toInt()
            strokeWidth = 2 * density
        }
        private val line = Paint().apply {
            color = 0x80FFFFFF.toInt()
            strokeWidth = 1f
        }

        init {
            visibility = GONE
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            for (i in 1..2) {
                canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, line)
                canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, line)
            }
            val inset = border.strokeWidth / 2f
            canvas.drawRoundRect(inset, inset, w - inset, h - inset, 6 * density, 6 * density, border)
        }
    }

    private companion object {
        const val MAX_WIDTH_DP = 290
    }
}
