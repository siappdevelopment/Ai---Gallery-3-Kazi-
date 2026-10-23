package com.example.thirdgallery.adapter

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.thirdgallery.database.MediaTypes
import com.example.thirdgallery.databinding.ItemViewerPageBinding
import com.example.thirdgallery.helper.MediaUris
import kotlin.math.max
import kotlin.math.min

/**
 * One full-screen page per item of the viewer. Pinch in / out zooms around the fingers (1x to 5x),
 * one finger moves a zoomed picture, a double tap zooms in or back out, and a single tap shows / hides
 * the controls. While a page is zoomed the pager does not swipe. A video page has a play button.
 */
class ViewerPagerAdapter(
    private val onToggleChrome: () -> Unit,
    private val onZoomChanged: (Boolean) -> Unit,
    private val onPlay: (Long) -> Unit,
) : RecyclerView.Adapter<ViewerPagerAdapter.ViewHolder>() {

    private var uids: List<Long> = emptyList()

    fun submit(list: List<Long>) {
        if (list == uids) return
        uids = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = uids.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemViewerPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(uids[position])

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        holder.resetZoom()
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(private val binding: ItemViewerPageBinding) : RecyclerView.ViewHolder(binding.root) {

        private var uid = 0L
        private var scale = 1f
        private var translateX = 0f
        private var translateY = 0f
        private var zoomed = false
        private var animator: ValueAnimator? = null

        private val scaleDetector = ScaleGestureDetector(
            binding.root.context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    animator?.cancel()
                    zoomTo(scale * detector.scaleFactor, detector.focusX, detector.focusY)
                    return true
                }
            },
        ).apply { isQuickScaleEnabled = false }

        private val detector = GestureDetector(
            binding.root.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (!scaleDetector.isInProgress) onToggleChrome()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    animateZoom(if (scale > ZOOMED_THRESHOLD) 1f else DOUBLE_TAP_SCALE, e.x, e.y)
                    return true
                }

                override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                    if (scale <= 1f || scaleDetector.isInProgress) return false
                    animator?.cancel()
                    translateX -= distanceX
                    translateY -= distanceY
                    applyTransform()
                    return true
                }
            },
        )

        init {
            binding.root.setOnTouchListener { v, event ->
                // The pager must not take the gesture while pinching or moving a zoomed picture.
                if (event.pointerCount > 1 || scale > 1f) v.parent?.requestDisallowInterceptTouchEvent(true)
                scaleDetector.onTouchEvent(event)
                detector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                true
            }
            binding.btnPlay.setOnClickListener { onPlay(uid) }
        }

        fun bind(uid: Long) {
            this.uid = uid
            resetZoom()
            binding.btnPlay.visibility = if (MediaTypes.typeOf(uid) == MediaTypes.VIDEO) View.VISIBLE else View.GONE
            val metrics = binding.root.resources.displayMetrics
            val side = maxOf(metrics.widthPixels, metrics.heightPixels)
            Glide.with(binding.ivMedia)
                .load(MediaUris.ofUid(uid))
                .override(side, side)
                .fitCenter()
                .dontAnimate()
                .into(binding.ivMedia)
        }

        /** Back to the fitted picture, without animation (new item, page scrolled away). */
        fun resetZoom() {
            animator?.cancel()
            scale = 1f
            translateX = 0f
            translateY = 0f
            applyTransform()
        }

        fun clear() {
            animator?.cancel()
            // The pager can be detached while the activity is being destroyed; Glide.with() throws then,
            // and its lifecycle has already cancelled these requests.
            if (isHostDestroyed(binding.root.context)) return
            Glide.with(binding.ivMedia).clear(binding.ivMedia)
        }

        /** Zooms to [target] keeping the point ([focusX], [focusY]) of the screen under the fingers. */
        private fun animateZoom(target: Float, focusX: Float, focusY: Float) {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(scale, target).apply {
                duration = ZOOM_MS
                addUpdateListener { zoomTo(it.animatedValue as Float, focusX, focusY) }
                start()
            }
        }

        private fun zoomTo(newScale: Float, focusX: Float, focusY: Float) {
            val target = newScale.coerceIn(1f, MAX_SCALE)
            val ratio = target / scale
            // Distances from the centre of the page (the scaling pivot).
            val dx = focusX - binding.ivMedia.width / 2f
            val dy = focusY - binding.ivMedia.height / 2f
            translateX = dx - (dx - translateX) * ratio
            translateY = dy - (dy - translateY) * ratio
            scale = target
            applyTransform()
        }

        private fun applyTransform() {
            val view = binding.ivMedia
            val limitX = panLimit(view.width.toFloat(), drawnWidth(view))
            val limitY = panLimit(view.height.toFloat(), drawnHeight(view))
            translateX = translateX.coerceIn(-limitX, limitX)
            translateY = translateY.coerceIn(-limitY, limitY)
            view.scaleX = scale
            view.scaleY = scale
            view.translationX = translateX
            view.translationY = translateY
            val nowZoomed = scale > ZOOMED_THRESHOLD
            if (nowZoomed != zoomed) {
                zoomed = nowZoomed
                onZoomChanged(nowZoomed)
            }
        }

        /** How far the zoomed picture may be moved before its edge leaves the screen edge. */
        private fun panLimit(viewSize: Float, drawnSize: Float): Float = max(0f, (drawnSize * scale - viewSize) / 2f)

        private fun drawnWidth(view: android.widget.ImageView): Float {
            val d = view.drawable ?: return view.width.toFloat()
            if (d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return view.width.toFloat()
            return d.intrinsicWidth * fit(view, d.intrinsicWidth, d.intrinsicHeight)
        }

        private fun drawnHeight(view: android.widget.ImageView): Float {
            val d = view.drawable ?: return view.height.toFloat()
            if (d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return view.height.toFloat()
            return d.intrinsicHeight * fit(view, d.intrinsicWidth, d.intrinsicHeight)
        }

        private fun fit(view: android.widget.ImageView, w: Int, h: Int): Float =
            min(view.width.toFloat() / w, view.height.toFloat() / h)

        private fun isHostDestroyed(context: Context): Boolean {
            var current: Context? = context
            while (current is ContextWrapper) {
                if (current is Activity) return current.isDestroyed
                current = current.baseContext
            }
            return false
        }
    }

    private companion object {
        const val MAX_SCALE = 5f
        const val DOUBLE_TAP_SCALE = 2f
        const val ZOOMED_THRESHOLD = 1.02f
        const val ZOOM_MS = 220L
    }
}
