package com.photovideo.gallerykz.memories.events

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Process
import android.os.SystemClock

/** Whether the app is in front and for how long, so a broadcast Android held back until the app woke up can be told from a real event. */
object EventProcessState {

    @Volatile
    private var startedActivities = 0

    @Volatile
    private var lastForegroundAt = 0L

    val isForeground: Boolean get() = startedActivities > 0

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                // The event screens themselves do not count as "the app is open".
                if (activity is EventPromptActivity) return
                if (startedActivities++ == 0) lastForegroundAt = SystemClock.elapsedRealtime()
            }

            override fun onActivityStopped(activity: Activity) {
                if (activity is EventPromptActivity) return
                if (startedActivities > 0) startedActivities--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** Milliseconds since the app came to the front; a large number while it is in the background. */
    fun msSinceForeground(): Long = if (startedActivities > 0) SystemClock.elapsedRealtime() - lastForegroundAt else Long.MAX_VALUE

    fun msSinceProcessStart(): Long = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
}
