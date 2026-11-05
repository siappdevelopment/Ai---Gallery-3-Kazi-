package com.photovideo.gallerykz.memories.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.activity.HighlightsDetailActivity
import com.photovideo.gallerykz.memories.helper.HighlightLabels
import com.photovideo.gallerykz.memories.helper.MemoryPrefs
import com.photovideo.gallerykz.memories.repo.HighlightsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wakes up every hour while "Memory notifications" is on and posts one notification about the first highlight there is
 * (the same card the Home page shows). It always uses the same notification id, so a new reminder replaces the earlier
 * one. After a reboot it sets the alarm again, because alarms do not survive one.
 *
 * Like the rest of Memory controls it follows "Date-based memories" (through the highlights it reads), but it does not
 * check "Show daily memories": that switch is about what the app shows, not about this reminder.
 */
class MemoryNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> MemoryNotificationScheduler.sync(app)
            ACTION_MEMORY_REMINDER -> {
                if (!MemoryPrefs.isMemoryNotifications(app)) {
                    // A leftover alarm of a switch that is off now.
                    MemoryNotificationScheduler.cancel(app)
                    return
                }
                val pending = goAsync()
                scope.launch {
                    try {
                        post(app)
                    } catch (e: Exception) {
                        // The next hour tries again.
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private fun post(app: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return

        // The first highlight of the day; nothing to say when there is none.
        val card = HighlightsRepository(app).load().firstOrNull() ?: return

        ensureChannel(app)
        val open = HighlightsDetailActivity.intent(app, card).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val content = PendingIntent.getActivity(app, CONTENT_REQUEST_CODE, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(app.getString(R.string.memory_notification_title))
            .setContentText(app.getString(R.string.memory_notification_text, HighlightLabels.title(app, card), card.count))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(content)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // The permission was taken away just now.
        }
    }

    private fun ensureChannel(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.memory_notification_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        const val ACTION_MEMORY_REMINDER = "com.photovideo.gallerykz.memories.action.MEMORY_REMINDER"
        private const val CHANNEL = "memory_reminders"
        private const val NOTIFICATION_ID = 7411
        private const val CONTENT_REQUEST_CODE = 7412

        /** Lives as long as the process, so a reminder being built is not cancelled when the receiver call returns. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
