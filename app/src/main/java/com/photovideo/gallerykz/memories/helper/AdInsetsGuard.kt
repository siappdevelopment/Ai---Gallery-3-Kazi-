package com.photovideo.gallerykz.memories.helper

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Collections
import java.util.WeakHashMap

/**
 * Keeps the screen behind a full-screen Google ad from jumping.
 *
 * A Google interstitial / app-open ad runs in its own window and hides the status bar. The screens of this app pad themselves
 * by the status bar height, so with the bar gone the screen behind the ad would slide up by that height (and slide back when the
 * ad closes). While such an ad is open, every screen therefore keeps being told the normal status bar height: nothing moves.
 *
 * Only the status bar is held, only while an ad window exists, and only for the screens' content; a screen that hides its own
 * status bar on purpose (the video player in full screen) is not affected once the ad is gone.
 */
object AdInsetsGuard {

    private const val GOOGLE_ADS_PACKAGE = "com.google.android.gms.ads"

    @Volatile
    private var adWindows = 0

    private val guarded: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap())

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (isAd(activity)) adWindows++
            }

            override fun onActivityStarted(activity: Activity) {
                if (!isAd(activity) && guarded.add(activity)) guard(activity)
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (isAd(activity)) adWindows = (adWindows - 1).coerceAtLeast(0)
                guarded.remove(activity)
            }

            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    private fun isAd(activity: Activity): Boolean = activity.javaClass.name.startsWith(GOOGLE_ADS_PACKAGE)

    /** The content area hands its children the normal status bar height while an ad window is open; otherwise nothing changes. */
    private fun guard(activity: Activity) {
        val content = activity.findViewById<View>(android.R.id.content) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            if (adWindows == 0) {
                insets
            } else {
                val normal = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())
                val now = insets.getInsets(WindowInsetsCompat.Type.statusBars())
                if (normal.top > now.top) {
                    WindowInsetsCompat.Builder(insets)
                        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(now.left, normal.top, now.right, now.bottom))
                        .build()
                } else {
                    insets
                }
            }
        }
    }
}
