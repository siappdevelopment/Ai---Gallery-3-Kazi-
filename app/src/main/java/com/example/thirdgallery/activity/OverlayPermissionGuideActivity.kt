package com.example.thirdgallery.activity

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatActivity
import android.content.ComponentName
import android.util.Log
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.ActivityOverlayPermissionGuideBinding
import com.example.thirdgallery.helper.OverlayPermission
import com.example.thirdgallery.launcher.LauncherHomeActivity
import java.lang.ref.WeakReference
import kotlin.math.PI
import kotlin.math.sin

/**
 * A short tutorial card drawn at the bottom of the system "Display over other apps" settings: the app's icon and name, a hint and
 * a looping demo of the toggle (the hand taps, the thumb slides right, the track turns blue).
 *
 * - It is a translucent, not focusable and not touch-modal activity with no dim: every tap, scroll and the Back key reach the
 *   settings screen underneath, and the real toggle is never covered (the card sits at the bottom).
 * - It is up for [SHOW_MS] (3 s) counted from the FIRST show of a settings visit, whatever restarts happen meanwhile (the retry
 *   start, a recreation), and is never shown again for the same visit. It closes earlier when the permission is granted (checked
 *   every 300 ms), when the screen is covered or left (onStop), or when the user taps anywhere (the tap still reaches the settings).
 *   Closing it only finishes this card; the system Settings screen below stays. The overlay permission is not granted yet, so this cannot be a window overlay.
 * - Only one card at a time. The system settings open their real screen a moment after they are started and can cover a card
 *   opened too early, so [show] starts the card again shortly after; a covered card closes itself on stop and the retry shows.
 */
class OverlayPermissionGuideActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var animator: ValueAnimator? = null
    private var binding: ActivityOverlayPermissionGuideBinding? = null

    private val dismiss = Runnable { closeGuide() }
    private val grantedCheck = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (OverlayPermission.isGranted(this@OverlayPermissionGuideActivity)) {
                Log.d(TAG, "guide: the permission is granted, the guide closes")
                closeGuide()
            } else {
                handler.postDelayed(this, CHECK_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A second card while one is alive (the retry start) is not wanted.
        if (current?.get()?.let { it !== this && !it.isFinishing && !it.isDestroyed } == true) {
            finish()
            return
        }
        // Outside taps are not consumed (the settings get them) but reported here, so any tap dismisses the card.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        )
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)
        val b = ActivityOverlayPermissionGuideBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        b.guideName.text = systemLabel()
        try {
            b.guideIcon.setImageDrawable(packageManager.getApplicationIcon(packageName))
        } catch (e: PackageManager.NameNotFoundException) {
            // The launcher icon from the layout stays.
        }
        b.guideRoot.setOnClickListener { closeGuide() }
        current = WeakReference(this)
        Log.d(TAG, "guide activity created")
    }

    /** The name the system lists for this app in its overlay-permission list: the label of the launcher activity (the app name when it has none). */
    private fun systemLabel(): CharSequence = try {
        packageManager.getActivityInfo(ComponentName(this, LauncherHomeActivity::class.java), 0).loadLabel(packageManager)
    } catch (e: Exception) {
        getString(R.string.app_name)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            closeGuide()
            return false
        }
        return super.onTouchEvent(event)
    }

    override fun onStart() {
        super.onStart()
        if (binding == null) return
        handler.removeCallbacks(dismiss)
        handler.removeCallbacks(grantedCheck)
        // The 3 s run from the first show of this visit, not from this start: a restarted card only gets what is left.
        val left = deadline - SystemClock.elapsedRealtime()
        if (left <= 0L) {
            closeGuide()
            return
        }
        handler.postDelayed(dismiss, left)
        handler.post(grantedCheck)
        startDemo()
    }

    override fun onStop() {
        // Covered by the settings screen, or the user went elsewhere: never leave the card behind.
        stopDemo()
        handler.removeCallbacks(dismiss)
        handler.removeCallbacks(grantedCheck)
        if (!isFinishing) finish()
        super.onStop()
    }

    override fun onDestroy() {
        stopDemo()
        handler.removeCallbacksAndMessages(null)
        if (current?.get() === this) current = null
        binding = null
        super.onDestroy()
    }

    private fun closeGuide() {
        if (!isFinishing && !isDestroyed) finish()
    }

    /** One loop: the hand taps (0-20%), the thumb slides and the track turns blue (20-45%), a pause, then it starts over from "off". */
    private fun startDemo() {
        val b = binding ?: return
        stopDemo()
        val density = resources.displayMetrics.density
        val travel = TRAVEL_DP * density
        val lift = LIFT_DP * density
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LOOP_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val p = it.animatedValue as Float
                val tap = if (p < TAP_END) sin(PI * p / TAP_END).toFloat() else 0f
                b.guideHand.translationY = -lift * tap
                b.guideHand.scaleX = 1f - 0.12f * tap
                b.guideHand.scaleY = 1f - 0.12f * tap
                val slide = if (p < TAP_END) 0f else if (p > SLIDE_END) 1f else (p - TAP_END) / (SLIDE_END - TAP_END)
                val shown = if (p > RESET_AT) 0f else slide
                b.guideThumb.translationX = travel * shown
                b.guideTrackOn.alpha = shown
            }
            start()
        }
    }

    private fun stopDemo() {
        animator?.cancel()
        animator = null
    }

    companion object {
        private const val TAG = "KaziGuide"
        private const val SHOW_MS = 3_000L
        private const val CHECK_MS = 300L
        private const val DEBOUNCE_MS = 4_000L
        private const val RETRY_MS = 700L
        private const val LOOP_MS = 2_800L
        private const val TAP_END = 0.2f
        private const val SLIDE_END = 0.45f
        private const val RESET_AT = 0.9f
        private const val TRAVEL_DP = 24f // the track is 56 dp wide, the thumb 24 dp with a 4 dp margin each side
        private const val LIFT_DP = 8f

        private val handler = Handler(Looper.getMainLooper())
        private var lastShownAt = 0L

        // End of the 3 s of the current settings visit (elapsedRealtime); set once by show().
        private var deadline = 0L
        private var current: WeakReference<OverlayPermissionGuideActivity>? = null

        /**
         * Call right after the system "Display over other apps" settings were started. Does nothing when the permission is already
         * granted; a second call within a few seconds (a double tap) is ignored.
         */
        fun show(context: Context) {
            val app = context.applicationContext
            if (OverlayPermission.isGranted(app)) return
            val now = SystemClock.elapsedRealtime()
            if (lastShownAt != 0L && now - lastShownAt < DEBOUNCE_MS) return
            lastShownAt = now
            deadline = now + SHOW_MS
            start(app)
            // The retry only while the 3 s of this visit are still running (it never starts a second period).
            handler.postDelayed({
                if (SystemClock.elapsedRealtime() < deadline && !OverlayPermission.isGranted(app)) start(app)
            }, RETRY_MS)
        }

        private fun start(context: Context) {
            try {
                context.startActivity(
                    Intent(context, OverlayPermissionGuideActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                )
            } catch (e: Exception) {
                Log.w(TAG, "guide activity could not start: ${e.javaClass.simpleName}")
            }
        }
    }
}
