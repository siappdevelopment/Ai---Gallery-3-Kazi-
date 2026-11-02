package com.photovideo.gallerykz.memories.callend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.photovideo.gallerykz.memories.R

/** The high-priority notification that opens the call summary (full-screen intent on the lock screen, a tap otherwise). */
object CallEndNotifier {

    private const val CHANNEL = "call_end_full_screen"
    private const val ID = 1001

    fun post(context: Context, activityIntent: Intent) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
            val channel = NotificationChannel(CHANNEL, context.getString(R.string.callend_channel_name), NotificationManager.IMPORTANCE_HIGH)
            channel.description = context.getString(R.string.callend_channel_desc)
            channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            manager.createNotificationChannel(channel)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val content = PendingIntent.getActivity(context, ID * 10 + 3, Intent(activityIntent), flags)
        val fullScreen = PendingIntent.getActivity(context, ID * 10 + 2, Intent(activityIntent), flags)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(context.getString(R.string.callend_notification_title))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(content)
            .setFullScreenIntent(fullScreen, true)
            .build()
        try {
            manager.notify(ID, notification)
            CallEndLog.d("notification posted")
        } catch (e: SecurityException) {
            CallEndLog.d("notification not allowed: $e")
        }
    }

    fun cancel(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(ID)
    }
}
