package com.example.thirdgallery.activity

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.thirdgallery.databinding.ActivityHighlightsStartBinding
import com.example.thirdgallery.helper.MediaUris

/**
 * The highlight slideshow: the media of the highlight, one after the other, cross-fading full screen. It loops
 * until the user taps anywhere, which closes it and returns to the highlight. It pauses while the app is in the
 * background and picks up at the same item.
 */
class HighlightsStartActivity : AppCompatActivity() {

    private var binding: ActivityHighlightsStartBinding? = null
    private val handler = Handler(Looper.getMainLooper())
    private var uids = LongArray(0)
    private var index = 0
    private var failures = 0
    private var pending: CustomTarget<Drawable>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        uids = intent.getLongArrayExtra(EXTRA_UIDS) ?: LongArray(0)
        if (uids.isEmpty()) {
            finish()
            return
        }
        index = (savedInstanceState?.getInt(KEY_INDEX) ?: 0).coerceIn(0, uids.size - 1)

        // Edge to edge with the system bars hidden; swiping from an edge shows them briefly.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val b = ActivityHighlightsStartBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        WindowInsetsControllerCompat(window, b.root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        // A tap anywhere closes the slideshow and returns to the highlight.
        b.root.setOnClickListener { finish() }
        b.tvHint.postDelayed({ binding?.tvHint?.animate()?.alpha(0f)?.setDuration(FADE_MS)?.start() }, HINT_MS)

        showFirst(b)
    }

    override fun onStart() {
        super.onStart()
        if (binding != null) scheduleNext()
    }

    override fun onStop() {
        // Paused in the background: nothing is loaded or timed until the screen is back.
        handler.removeCallbacksAndMessages(null)
        cancelPending()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_INDEX, index)
    }

    private fun showFirst(b: ActivityHighlightsStartBinding) {
        load(index) { drawable ->
            b.ivFront.setImageDrawable(drawable)
            scheduleNext()
        }
    }

    private fun scheduleNext() {
        handler.removeCallbacksAndMessages(null)
        if (uids.size > 1) handler.postDelayed({ advance() }, SHOW_MS)
    }

    /** Loads the next item behind the current one, then cross-fades to it. */
    private fun advance() {
        val b = binding ?: return
        val next = (index + 1) % uids.size
        load(next) { drawable ->
            index = next
            failures = 0
            b.ivBack.setImageDrawable(drawable)
            b.ivFront.animate().cancel()
            b.ivFront.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                if (binding == null) return@withEndAction
                b.ivFront.setImageDrawable(drawable)
                b.ivFront.alpha = 1f
                b.ivBack.setImageDrawable(null)
                scheduleNext()
            }.start()
        }
    }

    /** Decodes item [position] at screen size; an item that is gone is skipped, and the slideshow ends if none can load. */
    private fun load(position: Int, onReady: (Drawable) -> Unit) {
        cancelPending()
        val metrics = resources.displayMetrics
        val target = object : CustomTarget<Drawable>(metrics.widthPixels, metrics.heightPixels) {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                if (isFinishing || isDestroyed) return
                onReady(resource)
            }

            override fun onLoadFailed(errorDrawable: Drawable?) {
                if (isFinishing || isDestroyed) return
                failures++
                if (failures >= uids.size) {
                    finish()
                    return
                }
                // Skip the item that cannot be shown.
                index = position
                handler.post { advance() }
            }

            override fun onLoadCleared(placeholder: Drawable?) = Unit
        }
        pending = target
        Glide.with(applicationContext).load(MediaUris.ofUid(uids[position])).fitCenter().into(target)
    }

    private fun cancelPending() {
        pending?.let { Glide.with(applicationContext).clear(it) }
        pending = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cancelPending()
        binding?.let {
            it.ivFront.animate().cancel()
            it.ivFront.setImageDrawable(null)
            it.ivBack.setImageDrawable(null)
        }
        binding = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_UIDS = "slideshow_uids"
        private const val KEY_INDEX = "slideshow_index"
        private const val SHOW_MS = 3_000L
        private const val FADE_MS = 700L
        private const val HINT_MS = 2_500L

        /** [uids] are the media to play, in playing order. */
        fun intent(context: Context, uids: List<Long>): Intent =
            Intent(context, HighlightsStartActivity::class.java).putExtra(EXTRA_UIDS, uids.toLongArray())
    }
}
