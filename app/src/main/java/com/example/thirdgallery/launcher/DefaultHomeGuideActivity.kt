package com.example.thirdgallery.launcher

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.util.Log
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.ActivityDefaultHomeGuideBinding

/**
 * A short tutorial drawn over the system "Default home app" settings: the app's icon and name, a hint and a
 * hand tapping the radio. It lasts [SHOW_MS] and then goes away by itself. It can neither be touched nor take
 * focus, so every tap and the Back key still reach the settings screen underneath.
 *
 * Two ways to draw it, chosen by [show]:
 * - "Display over other apps" granted: a real overlay window, which is always above the settings screen.
 * - Otherwise this translucent activity. The system settings often open their real screen a moment after
 *   they are started (and that screen would cover an activity opened at the same time), so the activity is
 *   opened again shortly after; the first, covered one closes itself as soon as it is stopped.
 */
class DefaultHomeGuideActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val dismiss = Runnable { closeGuide() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Not focusable and not touch-modal: taps outside the card go straight to the settings screen, and the
        // window is told about them (ACTION_OUTSIDE) so it can close. A tap on the card itself closes it too.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        )
        // The window is only as big as the card (taps outside it reach the settings), centred; the theme dims behind it.
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.CENTER)
        Log.d(TAG, "guide activity created")
        val binding = ActivityDefaultHomeGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fill(binding)
        binding.guideCard.setOnClickListener { closeGuide() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            closeGuide()
            return false
        }
        return super.onTouchEvent(event)
    }

    /** Safe to call any number of times (timer, tap, stop). */
    private fun closeGuide() {
        if (!isFinishing && !isDestroyed) finish()
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(dismiss)
        handler.postDelayed(dismiss, SHOW_MS)
    }

    override fun onStop() {
        // Covered by the settings screen, or the user went elsewhere: never leave the guide behind.
        handler.removeCallbacks(dismiss)
        Log.d(TAG, "guide activity stopped (covered or left), it closes")
        if (!isFinishing) finish()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(dismiss)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KaziGuide"
        private const val SHOW_MS = 2_000L
        private const val DEBOUNCE_MS = 3_000L
        private const val OVERLAY_DELAY_MS = 500L
        private const val ACTIVITY_RETRY_MS = 700L
        private const val ACTIVITY_AFTER_OVERLAY_MS = 300L
        private const val OVERLAY_CHECK_MS = 700L
        private const val MAX_PASS_THROUGH_ALPHA = 0.8f
        private const val DIM_COLOR = 0x80000000.toInt()

        private val handler = Handler(Looper.getMainLooper())
        private var lastShownAt = 0L
        private var overlay: View? = null
        private var overlayDim: View? = null
        private var overlayWatcher: View? = null
        private var overlayManager: WindowManager? = null
        private var overlayDrawn = false
        private val removeOverlay = Runnable { removeOverlayNow() }

        /**
         * Call right after the system Default home settings were started. A second call within a few seconds
         * (a double tap) does nothing.
         */
        fun show(context: Context) {
            Log.d(TAG, "show() called (overlay permission=${canOverlay(context.applicationContext)})")
            val now = SystemClock.elapsedRealtime()
            if (lastShownAt != 0L && now - lastShownAt < DEBOUNCE_MS) {
                Log.d(TAG, "show() ignored: called again within ${DEBOUNCE_MS}ms")
                return
            }
            lastShownAt = now
            val app = context.applicationContext
            if (canOverlay(app)) {
                Log.d(TAG, "guide: overlay window in ${OVERLAY_DELAY_MS}ms")
                handler.postDelayed({ showOverlay(app) }, OVERLAY_DELAY_MS)
                // Some system settings screens hide overlay windows of other apps (anti-tapjacking, common on Xiaomi / HyperOS and
                // Android 12+): the overlay is then added without error and still never seen. So the guide activity is
                // opened as well, a moment after the settings are in front. Holding the overlay permission (and having the
                // overlay window up) is also what lets the app start it from the background. Both draw the same centred card.
                handler.postDelayed({ startActivityGuide(app) }, OVERLAY_DELAY_MS + ACTIVITY_AFTER_OVERLAY_MS)
            } else {
                Log.d(TAG, "guide: no overlay permission, starting the guide activity now and again in ${ACTIVITY_RETRY_MS}ms")
                startActivityGuide(app)
                handler.postDelayed({ startActivityGuide(app) }, ACTIVITY_RETRY_MS)
            }
        }

        private fun canOverlay(context: Context): Boolean =
            try {
                Settings.canDrawOverlays(context)
            } catch (e: Exception) {
                false
            }

        private fun startActivityGuide(context: Context) {
            try {
                context.startActivity(
                    Intent(context, DefaultHomeGuideActivity::class.java)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        )
                )
            } catch (e: Exception) {
                Log.w(TAG, "guide activity could not start: ${e.javaClass.simpleName} (the system may block starting it while the settings screen is in front)")
            }
        }

        private fun overlayType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        /**
         * Two windows: a full-screen dim, and the card on top of it. The card window is fully opaque (so nothing
         * shows through it) and only as big as the card; the dim is the part that may be see-through, and
         * Android 12 lets taps pass through a window only if it is at most 80% opaque.
         */
        private fun showOverlay(app: Context) {
            if (overlay != null) return
            try {
                val manager = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
                val themed = ContextThemeWrapper(app, R.style.Theme_ThirdGallery)
                val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

                val dim = View(themed).apply { setBackgroundColor(DIM_COLOR) }
                val dimParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    overlayType(), flags, PixelFormat.TRANSLUCENT
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dimParams.alpha = MAX_PASS_THROUGH_ALPHA
                manager.addView(dim, dimParams)
                overlayDim = dim
                overlayManager = manager
                Log.d(TAG, "guide: overlay dim window added")

                val binding = ActivityDefaultHomeGuideBinding.inflate(LayoutInflater.from(themed))
                fill(binding)
                val cardParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayType(), flags, PixelFormat.TRANSLUCENT
                )
                cardParams.gravity = Gravity.CENTER
                manager.addView(binding.root, cardParams)
                overlay = binding.root
                // Was the card ever drawn? A system settings screen can hide overlay windows: they are added without error
                // and then never drawn. The log tells which of the two happened (the activity guide covers that case).
                overlayDrawn = false
                binding.root.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
                    override fun onDraw() {
                        if (!overlayDrawn) {
                            overlayDrawn = true
                            Log.d(TAG, "guide: the overlay card was drawn (visible to the user)")
                        }
                    }
                })
                handler.postDelayed({
                    if (overlay != null && !overlayDrawn) {
                        Log.w(TAG, "guide: the overlay was added but not drawn after ${OVERLAY_CHECK_MS}ms (hidden by the system settings screen?); the guide activity is the visible one")
                    }
                }, OVERLAY_CHECK_MS)
                Log.d(TAG, "guide: overlay card window added")

                // A 1px window in the corner: it is not touched itself, but is told about every tap anywhere
                // else (ACTION_OUTSIDE) while the tap still goes to the settings screen. That closes the guide.
                val watcher = View(themed)
                watcher.setOnTouchListener { _, event ->
                    // Removed after this touch is dispatched, never from inside it.
                    if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                        Log.d(TAG, "guide: a touch outside closes the overlay")
                        handler.post(removeOverlay)
                    }
                    false
                }
                val watcherParams = WindowManager.LayoutParams(
                    1, 1, overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT
                )
                watcherParams.gravity = Gravity.TOP or Gravity.START
                manager.addView(watcher, watcherParams)
                overlayWatcher = watcher
                handler.postDelayed(removeOverlay, SHOW_MS)
            } catch (e: Exception) {
                Log.w(TAG, "guide: overlay failed: ${e.javaClass.simpleName} ${e.message}, falling back to the activity")
                removeOverlayNow()
                startActivityGuide(app)
            }
        }

        private fun removeOverlayNow() {
            if (overlay != null) Log.d(TAG, "guide: overlay windows removed")
            handler.removeCallbacks(removeOverlay)
            val manager = overlayManager
            val views = listOfNotNull(overlay, overlayDim, overlayWatcher)
            overlay = null
            overlayDim = null
            overlayWatcher = null
            overlayManager = null
            if (manager == null) return
            views.forEach { view ->
                try {
                    manager.removeViewImmediate(view)
                } catch (e: Exception) {
                    // Already gone.
                }
            }
        }

        /** The name the system lists for this app's Home screen: the launcher activity's own label (the app name when it has none). */
        private fun systemLabel(context: Context): CharSequence = try {
            val pm = context.packageManager
            pm.getActivityInfo(ComponentName(context, LauncherHomeActivity::class.java), 0).loadLabel(pm)
        } catch (e: Exception) {
            context.getString(R.string.app_name)
        }

        /** The app's real icon and name, so nothing in the guide is hardcoded. */
        private fun fill(binding: ActivityDefaultHomeGuideBinding) {
            val context = binding.root.context
            binding.guideTitle.text = systemLabel(context)
            try {
                binding.guideAppIcon.setImageDrawable(context.packageManager.getApplicationIcon(context.packageName))
            } catch (e: PackageManager.NameNotFoundException) {
                // No icon to show; the name and the hint still explain what to tap.
            }
        }
    }
}
