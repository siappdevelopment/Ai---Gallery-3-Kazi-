package com.photovideo.gallerykz.memories.launcher

import com.photovideo.gallerykz.memories.ads.AdAwareActivity

import com.photovideo.gallerykz.memories.ads.SwipeAds

import com.photovideo.gallerykz.memories.ads.LauncherAds

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.databinding.ActivityLauncherHomeBinding
import com.photovideo.gallerykz.memories.fragment.GalleryPageFragment
import kotlin.math.abs

/** What the pages and the drawer ask of the launcher activity. */
interface LauncherHost {
    fun openGallery()
    fun openDrawer()
    fun requestDefaultLauncher()

    /** Back to the home page from the report page. */
    fun showHome()
}

/**
 * The launcher: the app's main screen after the onboarding, and the phone's Home app when the user selects it.
 *
 * A pager holds the gallery (left page) and the home page (start page). A swipe up on the home page pulls the app drawer
 * up under the finger; a swipe right slides the gallery in. HOME + DEFAULT intent filter, singleTask, own task.
 */
class LauncherHomeActivity : AdAwareActivity(), LauncherHost, LauncherSettings.Listener {

    private var binding: ActivityLauncherHomeBinding? = null
    private val b get() = binding!!

    // The swipe-up gesture is owned by the activity, so a vertical drag never turns into a page swipe or an icon tap.
    private var gesture = GESTURE_NONE
    private var downX = 0f
    private var downY = 0f
    private var tracker: VelocityTracker? = null
    private var touchSlop = 0
    private var maxFling = 0

    private var wallpaperRefreshPending = false
    private var settledPage = PAGE_HOME
    private var pendingFromHome = -1

    private val defaultHome = DefaultHomeRequest(this) { onRoleResult() }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_PACKAGE_REMOVED && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                intent.data?.schemeSpecificPart?.let { RecentAppsStore.remove(context, it) }
            }
            LauncherApps.reload(context)
        }
    }

    /** Back: closes the drawer, or goes from the gallery or report page back to home. */
    private val backHandler = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (b.appsDrawer.isOpen) {
                b.appsDrawer.close()
            } else if (b.vpLauncher.currentItem != PAGE_HOME) {
                showHomePage(smooth = true)
            }
        }
    }

    /** Back on the home page: a launcher never closes, unless it is only an ordinary screen of the app. */
    private val homeRootBackHandler = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (LauncherNav.isHomeAppOrRoleHolder(this@LauncherHomeActivity)) return
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            isEnabled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // A translucent surface is cleared between frames; an opaque one piles up old frames after a recreate.
        window.setFormat(PixelFormat.TRANSLUCENT)
        super.onCreate(savedInstanceState)
        // The Home key or a widget can reach the launcher before the onboarding (permissions, default app) is finished:
        // the screen of the step the user is on opens instead (not the splash: it would show a second time).
        // Only on a fresh start: a recreate (theme or language change, rotation) must keep the user on this screen.
        if (savedInstanceState == null &&
            com.photovideo.gallerykz.memories.helper.OnboardingPrefs.getStep(this) != com.photovideo.gallerykz.memories.helper.OnboardingPrefs.STEP_DONE
        ) {
            startActivity(com.photovideo.gallerykz.memories.helper.OnboardingRouter.resume(this))
            finish()
            return
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val config = ViewConfiguration.get(this)
        touchSlop = config.scaledTouchSlop
        maxFling = config.scaledMaximumFlingVelocity

        binding = ActivityLauncherHomeBinding.inflate(layoutInflater)
        setContentView(b.root)
        wallpaperRefreshPending = savedInstanceState != null

        val pager = b.vpLauncher
        pager.adapter = PagerAdapter(this)
        pager.offscreenPageLimit = 1
        val start = when {
            intent.getBooleanExtra(LauncherNav.EXTRA_OPEN_GALLERY, false) -> PAGE_GALLERY
            else -> savedInstanceState?.getInt(KEY_PAGE, PAGE_HOME) ?: PAGE_HOME
        }
        pager.setCurrentItem(start, false)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // Leaving Home for the gallery (right swipe) or the report (left swipe): the swipe ad runs once the page settled.
                if (settledPage == PAGE_HOME && position != PAGE_HOME) pendingFromHome = position
                onPagerChanged()
            }

            override fun onPageScrollStateChanged(state: Int) {
                onPagerChanged()
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    val page = pager.currentItem
                    if (pendingFromHome == page && page != PAGE_HOME) SwipeAds.onSwipe(this@LauncherHomeActivity, right = page == PAGE_GALLERY)
                    pendingFromHome = -1
                    settledPage = page
                }
            }
        })

        b.appsDrawer.onClosed = { onPagerChanged() }
        b.appsDrawer.onSetDefaultClick = ::requestDefaultLauncher

        onBackPressedDispatcher.addCallback(this, homeRootBackHandler)
        onBackPressedDispatcher.addCallback(this, backHandler)

        LauncherSettings.register(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, packageReceiver, IntentFilter(Intent.ACTION_LOCALE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        // Loaded now so the first drawer opening already has the apps.
        LauncherApps.reload(this)
        onPagerChanged()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding?.let { outState.putInt(KEY_PAGE, it.vpLauncher.currentItem) }
    }

    override fun onResume() {
        super.onResume()
        // Back from an app started in the drawer: the drawer stays; the guard is only for the trip.
        binding?.appsDrawer?.appLaunchInProgress = false
        LauncherAds.onLauncherResumed(this)
        updateWindowBackground()
        binding?.appsDrawer?.refreshDefaultPromo()
    }

    /** Only what is opened from the gallery page counts for the click interstitial (the Main screen block); the home page and the drawer never do. */
    override fun adScreenForClicks(): com.photovideo.gallerykz.memories.ads.AdScreen? =
        if (binding?.vpLauncher?.currentItem == PAGE_GALLERY && binding?.appsDrawer?.isOpen != true) com.photovideo.gallerykz.memories.ads.AdScreen.MAIN else null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val binding = binding ?: return
        if (intent.getBooleanExtra(LauncherNav.EXTRA_OPEN_GALLERY, false)) {
            // A widget asked for the gallery.
            dismissTransientUi()
            binding.appsDrawer.close(animated = false)
            binding.vpLauncher.setCurrentItem(PAGE_GALLERY, false)
            return
        }
        if (intent.action == null) {
            // Started by the app itself (the splash after an icon tap, the Recent page): a fresh start on the home page.
            dismissTransientUi()
            binding.appsDrawer.close(animated = false)
            showHomePage(smooth = false)
            return
        }
        val isHomeIntent = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)
        if (!isHomeIntent) return
        // The Home KEY adds RESET_TASK_IF_NEEDED; the bare HOME intent the system sends when Back ends an app that was
        // started from the drawer is a RETURN: keep the open drawer, its scroll position and the search.
        val fromHomeKey = intent.flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED != 0
        if (binding.appsDrawer.appLaunchInProgress && !fromHomeKey) return
        dismissTransientUi()
        binding.appsDrawer.close(animated = false)
        showHomePage(smooth = false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // After a recreate (language or theme change) the translucent window can show a plain colour instead of the
        // wallpaper; once, with focus, on the home page, the flag is set again and the layout forced.
        if (hasFocus && wallpaperRefreshPending && binding?.vpLauncher?.currentItem == PAGE_HOME) {
            wallpaperRefreshPending = false
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.decorView.requestLayout()
            window.decorView.invalidate()
        }
    }

    override fun onStop() {
        // A launcher is not stopped by the Home button; the system overview or another app covering it does.
        dismissTransientUi()
        super.onStop()
    }

    override fun onDestroy() {
        LauncherSettings.unregister(this)
        try {
            unregisterReceiver(packageReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered.
        }
        tracker?.recycle()
        tracker = null
        binding = null
        super.onDestroy()
    }

    override fun onLauncherSettingsChanged() = Unit

    // ---- pages ----

    override fun openGallery() {
        binding?.vpLauncher?.setCurrentItem(PAGE_GALLERY, true)
    }

    override fun showHome() = showHomePage(smooth = true)

    override fun openDrawer() {
        val binding = binding ?: return
        if (binding.vpLauncher.currentItem != PAGE_HOME) return
        binding.appsDrawer.open()
        onPagerChanged()
    }

    private fun showHomePage(smooth: Boolean) {
        binding?.vpLauncher?.setCurrentItem(PAGE_HOME, smooth)
    }

    private fun homeFragment(): LauncherHomeFragment? =
        supportFragmentManager.findFragmentByTag("f$PAGE_HOME") as? LauncherHomeFragment

    /** Closes everything transient: the keyboard, a folder pop-up, a menu. */
    private fun dismissTransientUi() {
        homeFragment()?.dismissTransientUi()
        currentFocus?.let { getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    /** Page, scroll or drawer state changed: input, Back and the window follow. */
    private fun onPagerChanged() {
        val binding = binding ?: return
        // Only the settled home page can be swiped; the gallery page is left with Back or its own buttons.
        binding.vpLauncher.isUserInputEnabled =
            binding.vpLauncher.currentItem == PAGE_HOME && !binding.appsDrawer.isOpen && gesture != GESTURE_DRAWER
        backHandler.isEnabled = binding.appsDrawer.isOpen || binding.vpLauncher.currentItem != PAGE_HOME
        updateWindowBackground()
    }

    /**
     * Opaque and without the wallpaper on the settled gallery page; transparent with the wallpaper on the home page and
     * while scrolling between them. A window that is flagged for the wallpaper shows it before the first frame, which
     * was the flash on Back to the gallery page.
     */
    private fun updateWindowBackground() {
        val binding = binding ?: return
        val settledGallery = binding.vpLauncher.currentItem != PAGE_HOME &&
            binding.vpLauncher.scrollState == ViewPager2.SCROLL_STATE_IDLE
        if (settledGallery) {
            window.setBackgroundDrawable(ColorDrawable(ContextCompat.getColor(this, R.color.language_background)))
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        } else {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        }
    }

    // ---- swipe up for the drawer ----

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val binding = binding ?: return super.dispatchTouchEvent(ev)
        val drawer = binding.appsDrawer
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                gesture = GESTURE_NONE
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val canStart = !drawer.isOpen && binding.vpLauncher.currentItem == PAGE_HOME &&
                    binding.vpLauncher.scrollState == ViewPager2.SCROLL_STATE_IDLE
                if (gesture == GESTURE_NONE && canStart) {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        if (dy < 0 && abs(dy) > abs(dx) * VERTICAL_DOMINANCE) {
                            gesture = GESTURE_DRAWER
                            cancelChildTouches(ev)
                            binding.vpLauncher.isUserInputEnabled = false
                            drawer.beginDrag()
                            onPagerChanged()
                        } else if (abs(dx) > abs(dy)) {
                            // Sideways: the pager handles it.
                            gesture = GESTURE_PAGER
                        }
                    }
                }
                if (gesture == GESTURE_DRAWER) {
                    drawer.dragTo(downY - ev.rawY)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.addMovement(ev)
                val wasDrawer = gesture == GESTURE_DRAWER
                if (wasDrawer) {
                    tracker?.computeCurrentVelocity(1000, maxFling.toFloat())
                    val vy = if (ev.actionMasked == MotionEvent.ACTION_CANCEL) 0f else tracker?.yVelocity ?: 0f
                    drawer.endDrag(vy)
                }
                tracker?.recycle()
                tracker = null
                gesture = GESTURE_NONE
                if (wasDrawer) {
                    onPagerChanged()
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Sends a synthetic cancel so the view under the finger never completes the swipe as a tap. */
    private fun cancelChildTouches(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(ev)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    // ---- default launcher ----

    override fun requestDefaultLauncher() = defaultHome.request()

    /** The state is read from the system, never from the result code. */
    private fun onRoleResult() {
        binding?.appsDrawer?.refreshDefaultPromo()
        homeFragment()?.refreshDefaultPromo()
    }

    // ---- pager ----

    private class PagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount() = 3
        override fun createFragment(position: Int): Fragment = when (position) {
            PAGE_GALLERY -> GalleryPageFragment()
            PAGE_REPORT -> CleaningReportFragment()
            else -> LauncherHomeFragment()
        }
    }

    companion object {
        const val PAGE_GALLERY = 0
        const val PAGE_HOME = 1
        const val PAGE_REPORT = 2

        private const val KEY_PAGE = "launcher_page"
        private const val GESTURE_NONE = 0
        private const val GESTURE_DRAWER = 1
        private const val GESTURE_PAGER = 2
        private const val VERTICAL_DOMINANCE = 1.35f

        fun intent(context: Context): Intent = Intent(context, LauncherHomeActivity::class.java)
    }
}
