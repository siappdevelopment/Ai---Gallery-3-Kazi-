package com.photovideo.gallerykz.memories.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.photovideo.gallerykz.memories.helper.MemoryPrefs

/**
 * The hourly reminder of Memory controls: one inexact repeating alarm that wakes [MemoryNotificationReceiver]. Inexact
 * means no exact-alarm permission is needed, and Android may shift the time. The PendingIntent is always the same, so
 * scheduling again replaces the earlier alarm: it is safe at every app start and at every change of the switch.
 * Everything is best effort: a failure here never reaches the user.
 */
object MemoryNotificationScheduler {

    private const val REQUEST_CODE = 7410

    /** Starts (or restarts) the hourly alarm; the first reminder is one hour from now. */
    fun schedule(context: Context) {
        try {
            val app = context.applicationContext
            val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarms.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HOUR,
                AlarmManager.INTERVAL_HOUR,
                pendingIntent(app),
            )
        } catch (e: Exception) {
            // Best effort.
        }
    }

    fun cancel(context: Context) {
        try {
            val app = context.applicationContext
            (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent(app))
        } catch (e: Exception) {
            // Best effort.
        }
    }

    /** Makes the alarm match the switch: after an app start or a reboot. */
    fun sync(context: Context) {
        if (MemoryPrefs.isMemoryNotifications(context)) schedule(context) else cancel(context)
    }

    private fun pendingIntent(app: Context): PendingIntent {
        val intent = Intent(app, MemoryNotificationReceiver::class.java).setAction(MemoryNotificationReceiver.ACTION_MEMORY_REMINDER)
        return PendingIntent.getBroadcast(app, REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
