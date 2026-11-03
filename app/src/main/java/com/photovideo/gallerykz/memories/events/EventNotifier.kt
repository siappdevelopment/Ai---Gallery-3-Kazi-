package com.photovideo.gallerykz.memories.events

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.photovideo.gallerykz.memories.R

/** The fallback when the screen cannot be started directly: a high-priority notification with a full-screen intent. */
object EventNotifier {

    private const val CHANNEL = "event_screens_full_screen"

    fun post(context: Context, intent: Intent, kind: EventKind, title: CharSequence) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
            val channel = NotificationChannel(CHANNEL, context.getString(R.string.event_channel_name), NotificationManager.IMPORTANCE_HIGH)
            channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            manager.createNotificationChannel(channel)
        }
        // A stale notification of this kind must not suppress the new one.
        manager.cancel(kind.notificationId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val content = PendingIntent.getActivity(context, kind.notificationId * 10 + 3, Intent(intent), flags)
        val fullScreen = PendingIntent.getActivity(context, kind.notificationId * 10 + 2, Intent(intent), flags)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(content)
            .setFullScreenIntent(fullScreen, true)
            .build()
        try {
            manager.notify(kind.notificationId, notification)
        } catch (e: SecurityException) {
            // Notifications are not allowed: nothing to show.
        }
    }

    fun cancel(context: Context, kind: EventKind) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(kind.notificationId)
    }
}
