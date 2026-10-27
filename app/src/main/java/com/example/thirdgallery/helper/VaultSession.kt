package com.example.thirdgallery.helper

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import com.example.thirdgallery.activity.VaultSetupActivity
import java.lang.ref.WeakReference

/**
 * Whether the vault is open right now, and the auto-lock rules of the vault settings. Held in memory only,
 * so it is locked again whenever the process dies.
 *
 * Every vault screen reports when it starts and stops ([onScreenStart] / [onScreenStop]); the vault counts as
 * "left" when the last one has stopped, and the auto-lock option decides how long it then stays open:
 * Immediately (never), 1 or 5 minutes, or until the screen has been turned off.
 */
object VaultSession {

    private const val MINUTE_MS = 60_000L

    @Volatile
    private var unlocked = false
    private var visible = 0
    private var leftAt = 0L

    @Volatile
    private var screenWasOff = false

    /** A system dialog or one of the vault's own PIN screens is on top of a vault screen: that is not "leaving". */
    @Volatile
    private var held = false
    private var receiverRegistered = false
    private val screens = ArrayList<WeakReference<Activity>>()

    fun unlock(context: Context) {
        watchScreen(context)
        unlocked = true
        leftAt = 0L
        screenWasOff = false
    }

    fun lock() {
        unlocked = false
    }

    /**
     * Keeps the vault open while something outside the vault screens is shown on top of them (the system's
     * delete confirmation, the change-PIN screen). Call with false once it is answered.
     */
    fun hold(on: Boolean) {
        held = on
        if (!on) leftAt = 0L
    }

    /** Whether the vault can be opened without the PIN; applies the auto-lock option once nothing of it is on screen. */
    fun isOpen(context: Context): Boolean {
        if (unlocked && !held && visible == 0 && leftAt != 0L && expired(context)) unlocked = false
        return unlocked
    }

    /**
     * A vault screen came to the front. When the vault is locked every vault screen is closed and the PIN
     * screen takes over; returns whether the caller may show its content.
     */
    fun onScreenStart(activity: Activity): Boolean {
        watchScreen(activity)
        val open = isOpen(activity)
        visible++
        if (open) {
            leftAt = 0L
            return true
        }
        finishScreens(except = activity)
        activity.startActivity(VaultSetupActivity.intent(activity))
        activity.finish()
        return false
    }

    fun onScreenStop(changingConfigurations: Boolean) {
        visible = (visible - 1).coerceAtLeast(0)
        if (visible == 0 && !changingConfigurations && !held) {
            leftAt = SystemClock.elapsedRealtime()
            // The default option: leaving the vault locks it.
            val app = appContext
            if (unlocked && app != null && VaultPrefs.autoLock(app) == VaultPrefs.AUTO_LOCK_IMMEDIATELY) {
                unlocked = false
            }
        }
    }

    fun register(activity: Activity) {
        screens.removeAll { it.get() == null }
        screens.add(WeakReference(activity))
    }

    fun unregister(activity: Activity) {
        screens.removeAll { it.get() == null || it.get() === activity }
    }

    /** Closes every open vault screen (the vault was locked). */
    fun finishScreens(except: Activity? = null) {
        screens.mapNotNull { it.get() }.filter { it !== except }.forEach { it.finish() }
    }

    private fun expired(context: Context): Boolean {
        val away = SystemClock.elapsedRealtime() - leftAt
        return when (VaultPrefs.autoLock(context)) {
            VaultPrefs.AUTO_LOCK_IMMEDIATELY -> true
            VaultPrefs.AUTO_LOCK_1_MINUTE -> away > MINUTE_MS
            VaultPrefs.AUTO_LOCK_5_MINUTES -> away > 5 * MINUTE_MS
            else -> screenWasOff
        }
    }

    private var appContext: Context? = null

    private fun watchScreen(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (receiverRegistered) return
        receiverRegistered = true
        app.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    screenWasOff = true
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
        )
    }
}
