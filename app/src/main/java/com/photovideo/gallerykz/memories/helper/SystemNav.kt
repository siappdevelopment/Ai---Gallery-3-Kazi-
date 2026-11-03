package com.photovideo.gallerykz.memories.helper

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Collections
import java.util.WeakHashMap

/**
 * The system navigation bar is hidden on every screen (a swipe from the bottom shows it for a moment). A screen may ask for it
 * with [setVisible] (Language, Call End, Charging and Install / Uninstall while their ad is a big native ad).
 */
object SystemNav {

    private val shown: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap())
    private val listening: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap())

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                // The system brings the bar back after a dialog or the keyboard; it goes away again when the window regains focus.
                if (listening.add(activity)) {
                    activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus -> if (hasFocus) apply(activity) }
                }
                apply(activity)
            }

            override fun onActivityResumed(activity: Activity) = apply(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                shown.remove(activity)
                listening.remove(activity)
            }
        })
    }

    /** Shows (true) or hides (false, the default) the navigation bar of [activity]. */
    fun setVisible(activity: Activity, visible: Boolean) {
        if (visible) shown.add(activity) else shown.remove(activity)
        apply(activity)
    }

    private fun apply(activity: Activity) {
        try {
            if (activity.isFinishing || activity.isDestroyed) return
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            if (activity in shown) {
                controller.show(WindowInsetsCompat.Type.navigationBars())
            } else {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.navigationBars())
            }
        } catch (e: Exception) {
            Log.w("SystemNav", "navigation bar could not be updated: $e")
        }
    }
}
