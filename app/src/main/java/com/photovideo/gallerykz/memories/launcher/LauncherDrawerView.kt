package com.photovideo.gallerykz.memories.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.databinding.ViewLauncherDrawerBinding
import java.util.Locale
import kotlin.math.abs

/**
 * The app drawer. It lies over the pager and is pulled up under the finger by a swipe on the home page
 * ([beginDrag] / [dragTo] / [endDrag], driven by the activity), or opened and closed with [open] / [close]. Once open it
 * closes by dragging its top down, by Back or by the Home key. It stays open behind an app that was started from it, so
 * Back from that app lands on the same drawer.
 */
class LauncherDrawerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), LauncherApps.Listener, LauncherSettings.Listener {

    private val binding = ViewLauncherDrawerBinding.inflate(LayoutInflater.from(context), this)
    private val adapter = LauncherAppsAdapter(onClick = ::launch, onLongClick = ::showMenu)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    private var query = ""
    private var menu: PopupMenu? = null

    // Dragging the open panel down to close it.
    private var tracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var closeDragging = false

    var isOpen = false
        private set
    var isDragging = false
        private set

    /** True from the tap on an app until the launcher is resumed: the drawer must survive the trip to that app. */
    var appLaunchInProgress = false

    var onClosed: (() -> Unit)? = null
    var onSetDefaultClick: (() -> Unit)? = null

    init {
        isClickable = true
        visibility = GONE
        setBackgroundResource(R.color.language_background)

        binding.rvApps.layoutManager = GridLayoutManager(context, COLUMNS)
        binding.rvApps.adapter = adapter
        binding.rvApps.itemAnimator = null
        adapter.applySettings(context)

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                binding.btnClear.visibility = if (query.isEmpty()) GONE else VISIBLE
                render()
            }
        })
        binding.etSearch.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(v)
                true
            } else {
                false
            }
        }
        binding.btnClear.setOnClickListener { binding.etSearch.setText("") }
        binding.btnSetNow.setOnClickListener { onSetDefaultClick?.invoke() }

        // Room for the status bar on top and for the navigation bar or the keyboard below.
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.rvApps.setPadding(binding.rvApps.paddingLeft, 0, binding.rvApps.paddingRight, maxOf(bars.bottom, ime.bottom))
            setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        LauncherApps.register(this)
        LauncherSettings.register(this)
    }

    override fun onDetachedFromWindow() {
        LauncherApps.unregister(this)
        LauncherSettings.unregister(this)
        animate().cancel()
        menu?.dismiss()
        recycleTracker()
        super.onDetachedFromWindow()
    }

    override fun onAppsChanged(apps: List<LauncherApp>) = render()

    override fun onLauncherSettingsChanged() {
        adapter.applySettings(context)
        render()
    }

    // ---- open and close ----

    private fun hiddenY(): Float = maxOf(height, resources.displayMetrics.heightPixels).toFloat()

    /** Starts a finger-driven open: the panel is placed fully below the screen and then follows [dragTo]. */
    fun beginDrag() {
        animate().cancel()
        isOpen = true
        isDragging = true
        render()
        translationY = hiddenY()
        visibility = VISIBLE
        ViewCompat.requestApplyInsets(this)
    }

    /** Moves the panel up by [distancePx] from the bottom edge. */
    fun dragTo(distancePx: Float) {
        val hidden = hiddenY()
        translationY = (hidden - distancePx).coerceIn(0f, hidden)
    }

    /** The finger was lifted: open or close from the position and the speed (px/s, negative = up). */
    fun endDrag(velocityY: Float) {
        isDragging = false
        val hidden = hiddenY()
        val flingUp = velocityY < -minFling * FLING_FACTOR
        val flingDown = velocityY > minFling * FLING_FACTOR
        val projected = translationY + velocityY * PROJECTION_SECONDS
        if (flingUp || (projected < hidden * OPEN_THRESHOLD && !flingDown)) {
            settle(0f, velocityY, hideAfter = false)
        } else {
            settle(hidden, velocityY, hideAfter = true)
        }
    }

    fun open() {
        if (isOpen && translationY == 0f && !isDragging) return
        beginDrag()
        isDragging = false
        settle(0f, 0f, hideAfter = false)
    }

    fun close(animated: Boolean = true) {
        if (!isOpen) return
        isDragging = false
        menu?.dismiss()
        hideKeyboard(binding.etSearch)
        if (animated && visibility == VISIBLE && width > 0) {
            settle(hiddenY(), 0f, hideAfter = true)
        } else {
            finishClose()
        }
    }

    private fun settle(target: Float, velocityY: Float, hideAfter: Boolean) {
        animate().cancel()
        val distance = abs(target - translationY)
        val duration = if (abs(velocityY) > 1f) (distance / abs(velocityY) * 1000f).toLong().coerceIn(120L, 320L) else 260L
        val anim = animate()
            .translationY(target)
            .setDuration(duration)
            .setInterpolator(if (hideAfter) ACCELERATE else DECELERATE)
            .setListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    animate().setListener(null)
                    if (!cancelled && hideAfter) finishClose()
                }
            })
        anim.start()
    }

    private fun finishClose() {
        animate().cancel()
        animate().setListener(null)
        isOpen = false
        isDragging = false
        visibility = GONE
        translationY = 0f
        binding.etSearch.setText("")
        binding.rvApps.scrollToPosition(0)
        onClosed?.invoke()
    }

    /** Shows or hides the default-home card (only visible while this app is not the Home app). */
    fun refreshDefaultPromo() {
        binding.promo.visibility = if (LauncherNav.isDefaultHome(context)) GONE else VISIBLE
    }

    // ---- list ----

    private fun filtered(): List<LauncherApp> {
        val all = LauncherApps.apps
        val q = query.trim().lowercase(Locale.getDefault())
        return if (q.isEmpty()) all else all.filter { it.label.lowercase(Locale.getDefault()).contains(q) }
    }

    private fun render() {
        val list = filtered()
        adapter.submitList(list)
        // Not loaded yet is a blank grid, not "No apps found".
        binding.tvNoApps.visibility = if (list.isEmpty() && LauncherApps.apps.isNotEmpty() && isOpen) VISIBLE else GONE
        binding.rvApps.visibility = if (list.isEmpty()) INVISIBLE else VISIBLE
        refreshDefaultPromo()
    }

    private fun launch(app: LauncherApp) {
        if (appLaunchInProgress) return
        var host: android.content.Context? = context
        while (host is android.content.ContextWrapper && host !is android.app.Activity) host = host.baseContext
        val activity = host as? android.app.Activity
        if (activity == null) launchNow(app) else com.photovideo.gallerykz.memories.ads.LauncherAds.onAppTap(activity) { launchNow(app) }
    }

    private fun launchNow(app: LauncherApp) {
        if (appLaunchInProgress) return
        // Set before the start: the launcher gets onUserLeaveHint and a bare HOME intent when the app returns.
        appLaunchInProgress = true
        if (!LauncherApps.launch(context, app)) {
            appLaunchInProgress = false
            Toast.makeText(context.applicationContext, R.string.launcher_cannot_open, Toast.LENGTH_SHORT).show()
            LauncherApps.reload(context)
        }
    }

    private fun showMenu(app: LauncherApp, anchor: View) {
        menu?.dismiss()
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, MENU_INFO, 0, R.string.launcher_app_info)
        if (LauncherApps.canUninstall(context, app)) popup.menu.add(0, MENU_UNINSTALL, 1, R.string.launcher_uninstall)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_INFO -> startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null)))
                // The home screen's package receiver reloads the list when the uninstall really happens.
                MENU_UNINSTALL -> startSafely(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null)))
            }
            true
        }
        menu = popup
        popup.show()
    }

    private fun startSafely(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(context.applicationContext, R.string.launcher_cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideKeyboard(view: View) {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    // ---- drag down to close ----

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isOpen || isDragging) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                closeDragging = false
                recycleTracker()
                tracker = VelocityTracker.obtain()
                track(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                track(ev)
                if (!closeDragging) {
                    val dy = ev.rawY - downY
                    val dx = ev.rawX - downX
                    // Only from the top of the list: otherwise the same drag scrolls the apps.
                    if (dy > touchSlop && dy > abs(dx) && !binding.rvApps.canScrollVertically(-1)) {
                        closeDragging = true
                        hideKeyboard(binding.etSearch)
                        menu?.dismiss()
                        animate().cancel()
                        animate().cancel()
                        // Start from the current finger position, so the panel does not jump.
                        downY = ev.rawY - translationY
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!closeDragging) recycleTracker()
        }
        return closeDragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!isOpen) return super.onTouchEvent(ev)
        if (closeDragging) {
            track(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> translationY = (ev.rawY - downY).coerceAtLeast(0f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    closeDragging = false
                    tracker?.computeCurrentVelocity(1000, maxFling.toFloat())
                    val vy = tracker?.yVelocity ?: 0f
                    recycleTracker()
                    val hidden = hiddenY()
                    val cancelled = ev.actionMasked == MotionEvent.ACTION_CANCEL
                    val flingDown = !cancelled && vy > minFling * FLING_FACTOR
                    val flingUp = !cancelled && vy < -minFling * FLING_FACTOR
                    val projected = translationY + (if (cancelled) 0f else vy * PROJECTION_SECONDS)
                    if (flingDown || (projected > hidden * CLOSE_THRESHOLD && !flingUp)) {
                        settle(hidden, vy, hideAfter = true)
                    } else {
                        settle(0f, vy, hideAfter = false)
                    }
                }
            }
            return true
        }
        // Everything else on the open panel is swallowed so it never reaches the pages below.
        return true
    }

    /** Feeds the velocity tracker with screen coordinates: the panel itself moves with the finger. */
    private fun track(ev: MotionEvent) {
        val t = tracker ?: return
        val copy = MotionEvent.obtain(ev)
        copy.setLocation(ev.rawX, ev.rawY)
        t.addMovement(copy)
        copy.recycle()
    }

    private fun recycleTracker() {
        tracker?.recycle()
        tracker = null
    }

    companion object {
        private const val COLUMNS = 4
        private const val OPEN_THRESHOLD = 0.65f
        private const val CLOSE_THRESHOLD = 0.12f
        private const val PROJECTION_SECONDS = 0.12f
        private const val FLING_FACTOR = 2
        private const val MENU_INFO = 1
        private const val MENU_UNINSTALL = 2
        private val DECELERATE = DecelerateInterpolator(1.4f)
        private val ACCELERATE = AccelerateInterpolator(1.4f)
    }
}
