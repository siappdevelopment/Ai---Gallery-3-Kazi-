package com.example.thirdgallery.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import com.example.thirdgallery.helper.EditRenderer
import com.example.thirdgallery.model.CropBox
import com.example.thirdgallery.model.Stroke
import com.example.thirdgallery.model.StrokePoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The picture of the photo editor (design: a 6dp rounded frame, at most 290dp wide): the picture with the colour matrix, the
 * brush strokes and the text on top, and, while cropping, the crop box with draggable corners. The frame shows the part of
 * the picture inside [setBox] (the whole picture while cropping). Strokes, text and the crop are stored as shares of the whole
 * picture, so they stay on the same spot of the picture when the crop changes.
 */
class EditCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.MATRIX }
    private val paintLayer = PaintLayer(context)
    private val cropLayer = CropLayer(context)

    private var bitmap: Bitmap? = null
    private var box = CropBox.FULL
    private val frameMatrix = Matrix()

    private var textDrag = false
    private var lastX = 0f
    private var lastY = 0f

    /** A brush stroke was finished by the finger. */
    var onStroke: ((Stroke) -> Unit)? = null

    /** The text is being dragged (its centre as shares of the whole picture) / the finger lifted. */
    var onTextMoved: ((Float, Float) -> Unit)? = null
    var onTextMoveEnd: (() -> Unit)? = null

    /** A crop corner or the crop box is being dragged / the finger lifted. */
    var onCropChanged: ((CropBox) -> Unit)? = null
    var onCropEnd: (() -> Unit)? = null

    init {
        // The frame is rounded, but the crop handles at its corners must not be cut off, so only the picture layers are clipped.
        clipChildren = false
        val round = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, 6 * density)
        }
        image.outlineProvider = round
        image.clipToOutline = true
        paintLayer.outlineProvider = round
        paintLayer.clipToOutline = true
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(paintLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(cropLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        paintLayer.onStroke = { onStroke?.invoke(it) }
        cropLayer.onChanged = { onCropChanged?.invoke(it) }
        cropLayer.onEnd = { onCropEnd?.invoke() }
    }

    /** The (already turned / mirrored) picture. */
    fun setBitmap(value: Bitmap?) {
        bitmap = value
        image.setImageBitmap(value)
        requestLayout()
        updateMatrix()
    }

    fun setColorMatrix(matrix: ColorMatrix?) {
        image.colorFilter = matrix?.let { ColorMatrixColorFilter(it) }
    }

    /** The part of the picture the frame shows. */
    fun setBox(value: CropBox) {
        if (value == box) return
        box = value
        paintLayer.box = value
        requestLayout()
        updateMatrix()
        paintLayer.invalidate()
    }

    fun setStrokes(list: List<Stroke>) {
        paintLayer.strokes = list
        paintLayer.invalidate()
    }

    fun setText(value: String, color: Int, x: Float, y: Float) {
        paintLayer.text = value
        paintLayer.textColor = color
        paintLayer.textX = x
        paintLayer.textY = y
        paintLayer.invalidate()
    }

    /** Draw mode: a finger draws strokes of [color]; [sizeShare] is the thickness as a share of the frame's width. */
    fun setBrush(enabled: Boolean, color: Int, sizeShare: Float) {
        paintLayer.drawing = enabled
        paintLayer.color = color
        paintLayer.sizeShare = sizeShare
    }

    /** Crop mode: the crop box with its handles on top of the whole picture; [ratio] locks width / height, null is free. */
    fun setCrop(enabled: Boolean, crop: CropBox, ratio: Float?) {
        cropLayer.visibility = if (enabled) View.VISIBLE else View.GONE
        cropLayer.setCrop(crop, ratio)
    }

    /** Text mode: a finger anywhere on the stage drags the text. */
    fun setTextDrag(enabled: Boolean) {
        textDrag = enabled
    }

    /** Touches of the whole stage around the frame (the editor passes them on): drags the text by how far the finger moved. */
    fun onStageTouch(event: MotionEvent): Boolean {
        if (!textDrag || width == 0 || height == 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.rawX
                lastY = event.rawY
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val x = (paintLayer.textX + (event.rawX - lastX) / width * box.width).coerceIn(box.left, box.right)
                val y = (paintLayer.textY + (event.rawY - lastY) / height * box.height).coerceIn(box.top, box.bottom)
                lastX = event.rawX
                lastY = event.rawY
                paintLayer.textX = x
                paintLayer.textY = y
                paintLayer.invalidate()
                onTextMoved?.invoke(x, y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onTextMoveEnd?.invoke()
        }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = minOf(MeasureSpec.getSize(widthMeasureSpec), (MAX_WIDTH_DP * density).toInt())
        val maxHeight = MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: Int.MAX_VALUE
        val aspect = frameAspect()
        var w = maxWidth.toFloat()
        var h = w / aspect
        if (h > maxHeight) {
            h = maxHeight.toFloat()
            w = h * aspect
        }
        val wi = w.toInt().coerceAtLeast(1)
        val hi = h.toInt().coerceAtLeast(1)
        setMeasuredDimension(wi, hi)
        val exact = MeasureSpec.makeMeasureSpec(wi, MeasureSpec.EXACTLY)
        val exactH = MeasureSpec.makeMeasureSpec(hi, MeasureSpec.EXACTLY)
        image.measure(exact, exactH)
        paintLayer.measure(exact, exactH)
        cropLayer.measure(exact, exactH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
    }

    /** Width / height of the frame: the shown part of the picture in pixels. */
    private fun frameAspect(): Float {
        val bmp = bitmap ?: return 3f / 4f
        val w = box.width * bmp.width
        val h = box.height * bmp.height
        return if (w > 0f && h > 0f) w / h else 3f / 4f
    }

    /** Scales and moves the picture so the shown part fills the frame. */
    private fun updateMatrix() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        val scale = width / (box.width * bmp.width)
        frameMatrix.setScale(scale, scale)
        frameMatrix.postTranslate(-box.left * bmp.width * scale, -box.top * bmp.height * scale)
        image.imageMatrix = frameMatrix
    }

    /** Brush strokes drawn with the finger, the finished strokes and the text. */
    private class PaintLayer(context: Context) : View(context) {

        var strokes: List<Stroke> = emptyList()
        var box = CropBox.FULL
        var drawing = false
        var color = 0
        var sizeShare = 0.03f
        var text = ""
        var textColor = 0
        var textX = 0.5f
        var textY = 0.5f
        var onStroke: ((Stroke) -> Unit)? = null

        private var current: MutableList<StrokePoint>? = null

        override fun onDraw(canvas: Canvas) {
            if (width == 0 || height == 0) return
            // The picture's size in frame pixels; the same drawing code as the saved picture then draws on it.
            val iw = width / box.width
            val ih = height / box.height
            canvas.save()
            canvas.translate(-box.left * iw, -box.top * ih)
            EditRenderer.drawStrokes(canvas, strokes, iw, ih)
            current?.let { EditRenderer.drawStrokes(canvas, listOf(Stroke(color, sizeShare * box.width, it)), iw, ih) }
            EditRenderer.drawText(canvas, text, textColor, textX * iw, textY * ih, iw)
            canvas.restore()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!drawing || width == 0 || height == 0) return false
            val x = box.left + (event.x / width).coerceIn(0f, 1f) * box.width
            val y = box.top + (event.y / height).coerceIn(0f, 1f) * box.height
            val point = StrokePoint(x, y)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    current = mutableListOf(point)
                }
                MotionEvent.ACTION_MOVE -> current?.add(point)
                MotionEvent.ACTION_UP -> {
                    current?.let { onStroke?.invoke(Stroke(color, sizeShare * box.width, it.toList())) }
                    current = null
                }
                MotionEvent.ACTION_CANCEL -> current = null
            }
            invalidate()
            return true
        }
    }

    /** The crop box over the whole picture: dimmed outside, thirds lines, a round handle on each corner to drag it. */
    private class CropLayer(context: Context) : View(context) {

        private val density = resources.displayMetrics.density
        private val rect = RectF()
        private var crop = CropBox.FULL
        private var ratio: Float? = null
        private var activeCorner = -1
        private var moving = false
        private var grabX = 0f
        private var grabY = 0f
        private var dragging = false

        var onChanged: ((CropBox) -> Unit)? = null
        var onEnd: (() -> Unit)? = null

        private val dim = Paint().apply { color = 0x99000000.toInt() }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFFFFFFF.toInt()
            strokeWidth = 2 * density
        }
        private val line = Paint().apply {
            color = 0x80FFFFFF.toInt()
            strokeWidth = 1f
        }
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFFA457F.toInt()
            strokeWidth = 2.5f * density
        }

        init {
            visibility = GONE
        }

        fun setCrop(value: CropBox, lock: Float?) {
            ratio = lock
            // While a finger drags the box, the box in hand is the truth; the editor's copy follows it.
            if (!dragging) {
                crop = value
                placeRect()
            }
            invalidate()
        }

        private fun placeRect() {
            rect.set(crop.left * width, crop.top * height, crop.right * width, crop.bottom * height)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            placeRect()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, rect.top, dim)
            canvas.drawRect(0f, rect.bottom, w, h, dim)
            canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dim)
            canvas.drawRect(rect.right, rect.top, w, rect.bottom, dim)
            for (i in 1..2) {
                val x = rect.left + rect.width() * i / 3f
                val y = rect.top + rect.height() * i / 3f
                canvas.drawLine(x, rect.top, x, rect.bottom, line)
                canvas.drawLine(rect.left, y, rect.right, y, line)
            }
            canvas.drawRoundRect(rect, 4 * density, 4 * density, border)
            for (i in 0..3) {
                canvas.drawCircle(cornerX(i), cornerY(i), HANDLE_DP * density, handle)
                canvas.drawCircle(cornerX(i), cornerY(i), HANDLE_DP * density, handleRing)
            }
        }

        /** Corners: 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left. */
        private fun cornerX(i: Int) = if (i == 0 || i == 3) rect.left else rect.right
        private fun cornerY(i: Int) = if (i < 2) rect.top else rect.bottom

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (width == 0 || height == 0) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val reach = HIT_DP * density
                    activeCorner = (0..3).filter { hypot(event.x - cornerX(it), event.y - cornerY(it)) <= reach }
                        .minByOrNull { hypot(event.x - cornerX(it), event.y - cornerY(it)) } ?: -1
                    moving = activeCorner < 0 && rect.contains(event.x, event.y)
                    if (activeCorner < 0 && !moving) return false
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    if (activeCorner >= 0) {
                        grabX = event.x - cornerX(activeCorner)
                        grabY = event.y - cornerY(activeCorner)
                    } else {
                        grabX = event.x
                        grabY = event.y
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return false
                    if (activeCorner >= 0) dragCorner(event.x - grabX, event.y - grabY) else dragBox(event.x, event.y)
                    crop = CropBox(rect.left / width, rect.top / height, rect.right / width, rect.bottom / height)
                    invalidate()
                    onChanged?.invoke(crop)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return false
                    dragging = false
                    activeCorner = -1
                    moving = false
                    onEnd?.invoke()
                }
            }
            return true
        }

        private fun dragBox(x: Float, y: Float) {
            val dx = (x - grabX).coerceIn(-rect.left, width - rect.right)
            val dy = (y - grabY).coerceIn(-rect.top, height - rect.bottom)
            rect.offset(dx, dy)
            grabX = x
            grabY = y
        }

        /** Moves the active corner to ([x], [y]) with the opposite corner fixed; with a locked shape the box keeps it. */
        private fun dragCorner(x: Float, y: Float) {
            val w = width.toFloat()
            val h = height.toFloat()
            val left = activeCorner == 0 || activeCorner == 3
            val top = activeCorner < 2
            val ox = if (left) rect.right else rect.left
            val oy = if (top) rect.bottom else rect.top
            val minW = max(MIN_DP * density, w * CropBox.MIN_SIDE)
            val minH = max(MIN_DP * density, h * CropBox.MIN_SIDE)
            val roomW = if (left) ox else w - ox
            val roomH = if (top) oy else h - oy
            val lock = ratio
            var nw = abs(x - ox)
            var nh = abs(y - oy)
            if (lock == null) {
                nw = nw.coerceIn(min(minW, roomW), roomW)
                nh = nh.coerceIn(min(minH, roomH), roomH)
            } else {
                // The side the finger moved further drives the box, then it is cut down to what fits.
                nw = max(nw, nh * lock)
                val lowest = max(minW, minH * lock)
                nw = nw.coerceIn(min(lowest, roomW), max(min(lowest, roomW), min(roomW, roomH * lock)))
                nh = nw / lock
            }
            if (left) rect.left = ox - nw else rect.right = ox + nw
            if (top) rect.top = oy - nh else rect.bottom = oy + nh
        }
    }

    private companion object {
        const val MAX_WIDTH_DP = 290
        const val HANDLE_DP = 9
        const val HIT_DP = 32
        const val MIN_DP = 56
    }
}
