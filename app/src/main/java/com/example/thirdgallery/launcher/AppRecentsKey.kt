package com.example.thirdgallery.launcher

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference

/**
 * Opens the launcher's own Recent page when the Recents key is pressed on any screen of the app, but only while this
 * app is the phone's Home app. The key is detected through the system's "close system dialogs" broadcast
 * (reason "recentapps"), which is registered at run time while an activity of the app is visible. Phones that use
 * gesture navigation and send nothing keep their normal overview.
 */
object AppRecentsKey {

    private const val SAME_SIGNAL_MS = 300L

    private var started = 0
    private var receiver: BroadcastReceiver? = null
    private var top: WeakReference<Activity>? = null
    private var lastReason: String? = null
    private var lastSignalAt = 0L
    private var lastHomeAt = 0L

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                top = WeakReference(activity)
                if (started == 1) register(application)
            }

            override fun onActivityResumed(activity: Activity) {
                top = WeakReference(activity)
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) unregister(application)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** True when the Recent page may replace the system overview: this app must be the Home app. */
    fun canOpenRecent(context: Context): Boolean = LauncherNav.isHomeAppOrRoleHolder(context)

    private fun register(application: Application) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(context: Context, intent: Intent) {
                val reason = intent.getStringExtra("reason") ?: return
                onSignal(context, reason)
            }
        }
        receiver = r
        try {
            // The broadcast comes from the system, so the receiver must be exported (and registered at run time).
            ContextCompat.registerReceiver(application, r, IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), ContextCompat.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            receiver = null
        }
    }

    private fun unregister(application: Application) {
        val r = receiver ?: return
        receiver = null
        try {
            application.unregisterReceiver(r)
        } catch (e: IllegalArgumentException) {
            // Already gone.
        }
    }

    private fun onSignal(context: Context, reason: String) {
        val now = SystemClock.elapsedRealtime()
        // The same key reported twice in a row counts once.
        if (reason == lastReason && now - lastSignalAt < SAME_SIGNAL_MS) return
        lastReason = reason
        lastSignalAt = now
        when (reason) {
            "homekey" -> lastHomeAt = now
            "recentapps" -> {
                // Some phones report the Home key and the Recents key together.
                if (now - lastHomeAt < SAME_SIGNAL_MS) return
                openRecent(context)
            }
        }
    }

    private fun openRecent(context: Context) {
        if (!canOpenRecent(context)) return
        val activity = top?.get()
        if (activity is RecentActivity) return
        try {
            context.startActivity(
                Intent(context, RecentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        } catch (e: Exception) {
            // The overview simply stays as the system shows it.
        }
    }
}
