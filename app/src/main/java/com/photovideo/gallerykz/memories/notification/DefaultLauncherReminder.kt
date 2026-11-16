package com.photovideo.gallerykz.memories.notification

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.activity.SplashActivity
import com.photovideo.gallerykz.memories.ads.ReminderCfg
import com.photovideo.gallerykz.memories.helper.OnboardingPrefs
import com.photovideo.gallerykz.memories.launcher.LauncherNav
import java.util.concurrent.TimeUnit

/**
 * The "Make Gallery your home screen" reminder (Remote Config `launcher_home_screen.launcher_notification_push_*`).
 *
 * - Every config apply stores the four values and calls [sync]: OFF cancels everything; an interval of 15 minutes or more is one
 *   unique periodic work; a shorter one is a unique one-time chain (each run schedules the next), because WorkManager's periodic
 *   work cannot go below 15 minutes. The interval is 86400 / `push_time` seconds (24 = every hour).
 * - Every run re-reads the live state: it posts only while the reminder is ON, Gallery is NOT the Home app, the onboarding is done and
 *   the app is not on screen. While Gallery is the Home app the schedule is kept (only the posting is skipped), so switching to another
 *   launcher later brings the reminder back.
 * - The title / description are read at post time (the next reminder uses the latest text); an empty one uses the translated built-in.
 * - Tapping it opens the splash, which opens the Default app screen in reminder mode (system Home settings first) and then the launcher.
 *
 * Log tag `DefaultLauncherNav`.
 */
object DefaultLauncherReminder {

    /** Set on the splash intent by the notification; the splash then asks for the Home app and ends at the launcher. */
    const val EXTRA_FROM_REMINDER = "extra_from_default_launcher_reminder"

    private const val TAG = "DefaultLauncherNav"
    private const val FILE = "default_launcher_reminder"
    private const val KEY_SHOW = "show"
    private const val KEY_TIME = "time"
    private const val KEY_TITLE = "title"
    private const val KEY_DESC = "description"
    private const val KEY_LAST_POST = "last_post_at"

    private const val UNIQUE_WORK_NAME = "default_launcher_reminder_work"
    private const val UNIQUE_CHAIN_NAME = "default_launcher_reminder_chain"
    internal const val KEY_CHAIN = "reminder_chain"
    private const val CHANNEL = "default_launcher_reminder_channel"
    private const val NOTIFICATION_ID = 7001
    private const val REQUEST_CODE = 7001

    private const val DAY_SECONDS = 86_400
    private const val MIN_PERIODIC_SECONDS = 900
    private const val MIN_CHAIN_SECONDS = 5
    private const val DEFAULT_TIME = 24

    @Volatile
    private var startedActivities = 0

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- the app on screen / off screen ----

    /** Counts the started screens (the app is on screen while one is) and takes the notification away when the app is opened. */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0) {
                    Log.d(TAG, "app came to the foreground -> reminder notification dismissed")
                    dismiss(activity)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (startedActivities > 0) startedActivities--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    internal fun isAppInForeground(): Boolean = startedActivities > 0

    // ---- config ----

    /** A new config was applied: keeps its values on the phone (the worker reads them) and makes the schedule match. */
    fun onConfig(context: Context, cfg: ReminderCfg) {
        try {
            prefs(context).edit()
                .putBoolean(KEY_SHOW, cfg.show)
                .putInt(KEY_TIME, cfg.time)
                .putString(KEY_TITLE, cfg.title)
                .putString(KEY_DESC, cfg.description)
                .apply()
            Log.d(TAG, "RC applied: launcher_notification_push_show=${cfg.show}, push_time=${cfg.time}, title=\"${cfg.title}\", description=\"${cfg.description}\"" +
                if (!cfg.show) " (OFF: if you expected it ON, check that launcher_home_screen is in the applied normal_user / paid_user block of this build's parameter)" else "")
            sync(context)
        } catch (e: Exception) {
            Log.w(TAG, "reminder config failed: $e")
        }
    }

    private fun isOn(context: Context) = prefs(context).getBoolean(KEY_SHOW, false)

    /** True while the Remote Config has the reminder on. */
    fun isEnabled(context: Context) = isOn(context)

    private class Plan(val pushTime: Int, val seconds: Int) {
        val periodic: Boolean get() = seconds >= MIN_PERIODIC_SECONDS
    }

    /** `seconds = 86400 / push_time`; 0 / negative uses 24; more than 17280 is held at 5 seconds. */
    private fun plan(pushTime: Int): Plan {
        val value = if (pushTime <= 0) DEFAULT_TIME else pushTime
        val seconds = (DAY_SECONDS / value).coerceAtLeast(MIN_CHAIN_SECONDS)
        return Plan(pushTime, seconds)
    }

    private fun currentPlan(context: Context) = plan(prefs(context).getInt(KEY_TIME, DEFAULT_TIME))

    // ---- schedule ----

    /** Makes the schedule match the saved values. Safe to call any number of times (unique work: no duplicates). */
    fun sync(context: Context) {
        try {
            val app = context.applicationContext
            val work = WorkManager.getInstance(app)
            if (!isOn(app)) {
                cancelWork(app)
                Log.d(TAG, "reminder OFF -> work cancelled")
                return
            }
            val plan = currentPlan(app)
            Log.d(TAG, "Default Launcher status = ${if (LauncherNav.isHomeAppOrRoleHolder(app)) "DEFAULT" else "NOT_DEFAULT"}")
            Log.d(TAG, "Calculated interval = ${plan.seconds} s (86400 / push_time ${plan.pushTime})")
            if (!com.photovideo.gallerykz.memories.launcher.MiuiBackgroundStart.isAutoStartAllowed(app)) {
                Log.d(TAG, "WARNING: Xiaomi Autostart is OFF for this app: after the app is swiped away from Recents the phone stops its background work, so no reminder arrives until the app is opened again. Turn Autostart on (Security > Permissions > Autostart).")
            }
            if (plan.periodic) {
                work.cancelUniqueWork(UNIQUE_CHAIN_NAME)
                val request = PeriodicWorkRequestBuilder<DefaultLauncherReminderWorker>(plan.seconds.toLong(), TimeUnit.SECONDS)
                    .setInitialDelay(plan.seconds.toLong(), TimeUnit.SECONDS)
                    .build()
                work.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
                Log.d(TAG, "Scheduling strategy = PERIODIC, first reminder in ${plan.seconds} s, then every ${plan.seconds} s")
            } else {
                work.cancelUniqueWork(UNIQUE_WORK_NAME)
                // KEEP: a repeated config apply never postpones or doubles a pending run.
                work.enqueueUniqueWork(UNIQUE_CHAIN_NAME, ExistingWorkPolicy.KEEP, chainRequest(plan.seconds))
                Log.d(TAG, "Scheduling strategy = ONE_TIME_CHAIN, next run in ${plan.seconds} s (a run that is already pending is kept)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "reminder sync failed: $e")
        }
    }

    private fun chainRequest(seconds: Int) = OneTimeWorkRequestBuilder<DefaultLauncherReminderWorker>()
        .setInitialDelay(seconds.toLong(), TimeUnit.SECONDS)
        .setInputData(Data.Builder().putBoolean(KEY_CHAIN, true).build())
        .build()

    private fun cancelWork(app: Context) {
        val work = WorkManager.getInstance(app)
        work.cancelUniqueWork(UNIQUE_WORK_NAME)
        work.cancelUniqueWork(UNIQUE_CHAIN_NAME)
    }

    /** After every run of a chain: the next one, at the interval the config has now. */
    internal fun scheduleNextChainRun(context: Context) {
        try {
            val app = context.applicationContext
            if (!isOn(app)) {
                sync(app)
                return
            }
            val plan = currentPlan(app)
            if (plan.periodic) {
                sync(app)
                return
            }
            WorkManager.getInstance(app).enqueueUniqueWork(UNIQUE_CHAIN_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, chainRequest(plan.seconds))
            Log.d(TAG, "Next execution scheduled = in ${plan.seconds} s")
        } catch (e: Exception) {
            Log.w(TAG, "next reminder scheduling failed: $e")
        }
    }

    // ---- one run ----

    /** Returns false when the reminder was switched off (the work is cancelled then). */
    internal fun run(context: Context): Boolean {
        val app = context.applicationContext
        if (!isOn(app)) {
            cancelWork(app)
            Log.d(TAG, "run: reminder OFF -> stopped")
            return false
        }
        val isDefault = LauncherNav.isHomeAppOrRoleHolder(app)
        val step = OnboardingPrefs.getStep(app)
        val onboardingDone = step == OnboardingPrefs.STEP_DONE
        val foreground = isAppInForeground()
        Log.d(TAG, "run: RC show=true, push_time=${prefs(app).getInt(KEY_TIME, DEFAULT_TIME)}, Gallery is default Home=$isDefault, onboarding done=$onboardingDone (step=$step), app in foreground=$foreground")
        val reason = when {
            isDefault -> "already default".also { dismiss(app) }
            !onboardingDone -> "onboarding not finished"
            foreground -> "app in foreground"
            else -> null
        }
        if (reason != null) {
            Log.d(TAG, "Notification shown = false (SKIPPED: $reason)")
            return true
        }
        // A run that was held back (the phone stopped the work while the app was swiped away) can come in right behind the next one: one
        // reminder per half interval at most, so the two never post twice.
        val now = System.currentTimeMillis()
        val minGapMs = currentPlan(app).seconds * 500L
        val sinceLast = now - prefs(app).getLong(KEY_LAST_POST, 0L)
        if (sinceLast in 0 until minGapMs) {
            Log.d(TAG, "Notification shown = false (SKIPPED: the last one was ${sinceLast / 1000} s ago, less than half of the interval)")
            return true
        }
        val failure = post(app)
        if (failure == null) {
            prefs(app).edit().putLong(KEY_LAST_POST, now).apply()
            Log.d(TAG, "Notification shown = true")
        } else {
            Log.d(TAG, "Notification shown = false (NOT POSTED: $failure)")
        }
        return true
    }

    // ---- the notification ----

    /** Posts the reminder; null when it was posted, else the reason it was not (also logged by [run]). */
    private fun post(app: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return "POST_NOTIFICATIONS permission is not granted (Android 13+)"
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return "notifications are turned off for the app in system settings"

        ensureChannel(app)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = manager.getNotificationChannel(CHANNEL)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return "the notification channel is turned off by the user"
        }
        val p = prefs(app)
        val title = p.getString(KEY_TITLE, "").orEmpty().ifBlank { app.getString(R.string.default_launcher_reminder_title) }
        val text = p.getString(KEY_DESC, "").orEmpty().ifBlank { app.getString(R.string.default_launcher_reminder_text) }
        val open = Intent(app, SplashActivity::class.java)
            .putExtra(EXTRA_FROM_REMINDER, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val content = PendingIntent.getActivity(app, REQUEST_CODE, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(content)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        Log.d(TAG, "Notification text -> title=\"$title\" description=\"$text\"")
        return try {
            // One fixed id: a new reminder replaces the old one, they never stack.
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification)
            null
        } catch (e: SecurityException) {
            "SecurityException while posting: $e"
        }
    }

    private fun ensureChannel(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.default_launcher_reminder_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** Takes the reminder away (the app was opened, or Gallery became the Home app). The schedule is not touched. */
    fun dismiss(context: Context) {
        try {
            NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            // Nothing to take away.
        }
    }
}

/** One run of the reminder; always succeeds (a failure is logged, never retried) and a chain run schedules the next one. */
class DefaultLauncherReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val chain = inputData.getBoolean(DefaultLauncherReminder.KEY_CHAIN, false)
        Log.d("DefaultLauncherNav", "Worker started (${if (chain) "ONE_TIME_CHAIN" else "PERIODIC"}, attempt $runAttemptCount)")
        try {
            val keepGoing = DefaultLauncherReminder.run(applicationContext)
            if (chain && keepGoing) DefaultLauncherReminder.scheduleNextChainRun(applicationContext)
        } catch (e: Exception) {
            Log.w("DefaultLauncherNav", "reminder run failed: $e")
            if (chain) DefaultLauncherReminder.scheduleNextChainRun(applicationContext)
        }
        return Result.success()
    }
}
