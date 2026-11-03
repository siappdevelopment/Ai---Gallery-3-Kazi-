package com.photovideo.gallerykz.memories.events

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.helper.OverlayPermission
import com.photovideo.gallerykz.memories.launcher.LauncherNav

/**
 * Decides whether an event opens its screen and how: the gates of the skill (switched on, no call, no duplicate within
 * 1.5 s), then the way a background app is allowed to start a screen (the app is open, it is the default Home app, or it
 * has "Display over other apps" and shows a tiny overlay for a moment), and a full-screen notification as the last resort.
 */
object EventLauncher {

    private const val TAG = "KaziEvent"
    private const val DEBOUNCE_MS = 1_500L
    private const val HELD_BACK_WINDOW_MS = 3_500L
    private const val OVERLAY_START_DELAY_MS = 250L

    private var lastKind: EventKind? = null
    private var lastOpenAt = 0L

    /**
     * Android delivers broadcasts that were held while the app was frozen the moment it starts or comes forward; those are
     * old events and must not pop up on top of what the user opened the app for.
     */
    fun isHeldBackDelivery(): Boolean =
        EventProcessState.msSinceProcessStart() < HELD_BACK_WINDOW_MS || EventProcessState.msSinceForeground() < HELD_BACK_WINDOW_MS

    fun open(context: Context, kind: EventKind) {
        val app = context.applicationContext
        if (!EventPrefs.isEnabled(app, kind)) return
        if (isPhoneCallInProgress(app)) {
            Log.d(TAG, "${kind.key}: not shown during a call")
            return
        }
        val now = System.currentTimeMillis()
        if (kind == lastKind && now - lastOpenAt < DEBOUNCE_MS) return
        lastKind = kind
        lastOpenAt = now

        val screen = if (kind == EventKind.CHARGE_IN || kind == EventKind.CHARGE_OUT) ChargingScreenActivity::class.java else InstallUninstallScreenActivity::class.java
        val intent = Intent(app, screen)
            .putExtra(EventPromptActivity.EXTRA_KIND, kind.key)
            .putExtra(EventPromptActivity.EXTRA_LAUNCHED_AT, now)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val show = { show(app, intent, kind) }
        if (Looper.myLooper() == Looper.getMainLooper()) show() else Handler(Looper.getMainLooper()).post(show)
    }

    private fun show(app: Context, intent: Intent, kind: EventKind) {
        val foreground = EventProcessState.isForeground
        val defaultHome = LauncherNav.isHomeAppOrRoleHolder(app)
        val overlay = OverlayPermission.isGranted(app)
        // Without any of these a background start cannot bring the screen up: Android would park it, and it would
        // appear later, when the user opens the app.
        if (!foreground && !defaultHome && !overlay) {
            Log.d(TAG, "${kind.key}: app in the background without overlay permission, not shown")
            return
        }
        if (foreground || defaultHome) {
            if (start(app, intent)) return
        } else if (startWithOverlay(app, intent, kind)) {
            return
        }
        EventNotifier.post(app, intent, kind, app.getString(subtitleOf(kind)))
    }

    private fun start(app: Context, intent: Intent): Boolean = try {
        app.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.d(TAG, "direct start failed: $e")
        false
    }

    /** An app with a visible overlay window may start a screen from the background: a 1 px window is shown for a moment. */
    private fun startWithOverlay(app: Context, intent: Intent, kind: EventKind): Boolean {
        val manager = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        val view = View(app)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            1, 1, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        )
        try {
            manager.addView(view, params)
        } catch (e: Exception) {
            Log.d(TAG, "overlay window failed: $e")
            return false
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (!start(app, intent)) EventNotifier.post(app, intent, kind, app.getString(subtitleOf(kind)))
            try {
                manager.removeView(view)
            } catch (e: Exception) {
                // Already gone.
            }
        }, OVERLAY_START_DELAY_MS)
        return true
    }

    private fun subtitleOf(kind: EventKind): Int = when (kind) {
        EventKind.CHARGE_IN -> R.string.event_charge_in_title
        EventKind.CHARGE_OUT -> R.string.event_charge_out_title
        EventKind.INSTALL -> R.string.event_install_sub
        EventKind.UNINSTALL -> R.string.event_uninstall_sub
    }

    /** A call is ringing or going: the telephony state, or the audio mode when the phone state cannot be read. */
    private fun isPhoneCallInProgress(context: Context): Boolean {
        try {
            val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            @Suppress("DEPRECATION")
            if (telephony != null && telephony.callState != TelephonyManager.CALL_STATE_IDLE) return true
        } catch (e: Exception) {
            // No permission to read it: the audio mode below still tells.
        }
        return try {
            val mode = (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode
            mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
        } catch (e: Exception) {
            false
        }
    }
}
