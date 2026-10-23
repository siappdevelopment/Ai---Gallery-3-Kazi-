package com.example.thirdgallery.adapter

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.thirdgallery.database.MediaTypes
import com.example.thirdgallery.databinding.ItemViewerPageBinding
import com.example.thirdgallery.helper.MediaUris

/**
 * One full-screen page per item of the viewer. A tap shows / hides the controls, a double tap zooms
 * in and out (2x, like the design) and a video page has a play button.
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
        private var zoomed = false

        private val detector = GestureDetector(
            binding.root.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    onToggleChrome()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    setZoomed(!zoomed)
                    return true
                }
            },
        )

        init {
            binding.root.setOnTouchListener { v, event ->
                val handled = detector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                handled
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

        fun resetZoom() {
            if (!zoomed) return
            binding.ivMedia.animate().cancel()
            binding.ivMedia.scaleX = 1f
            binding.ivMedia.scaleY = 1f
            zoomed = false
            onZoomChanged(false)
        }

        fun clear() {
            // The pager can be detached while the activity is being destroyed; Glide.with() throws then,
            // and its lifecycle has already cancelled these requests.
            if (isHostDestroyed(binding.root.context)) return
            Glide.with(binding.ivMedia).clear(binding.ivMedia)
        }

        private fun setZoomed(zoom: Boolean) {
            zoomed = zoom
            val scale = if (zoom) ZOOM_SCALE else 1f
            binding.ivMedia.animate().scaleX(scale).scaleY(scale).setDuration(ZOOM_MS).start()
            onZoomChanged(zoom)
        }

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
        const val ZOOM_SCALE = 2f
        const val ZOOM_MS = 300L
    }
}
