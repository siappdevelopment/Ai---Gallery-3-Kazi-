package com.example.thirdgallery.callend

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Follows the phone state (ringing, in a call, idle) and opens the call summary when a call ends. It does nothing unless the
 * user switched the screen on in Settings and allowed phone access. The state is kept in the process, like a call lasts.
 */
class PhoneCallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null || intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val enabled = CallEndPrefs.isEnabled(context)
        val permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        CallEndLog.d("PHONE_STATE state=${intent.getStringExtra(TelephonyManager.EXTRA_STATE)} enabled=$enabled permission=$permitted")
        if (!enabled || !permitted) return
        try {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            if (number.isNullOrEmpty()) {
                @Suppress("DEPRECATION")
                number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.takeIf { it.isNotEmpty() } ?: number
            }
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    startMs = System.currentTimeMillis()
                    ringing = true
                    shown = false
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    startMs = System.currentTimeMillis()
                    if (ringing) answered = true
                    shown = false
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    if (shown) {
                        reset()
                        return
                    }
                    val kind = when {
                        answered -> CallKind.INCOMING
                        ringing -> CallKind.MISSED
                        else -> CallKind.OUTGOING
                    }
                    shown = true
                    CallEndLog.d("call ended kind=$kind number=${number ?: "none"} durationMs=${System.currentTimeMillis() - startMs}")
                    CallEndLauncher.openAfterCall(context, number, startMs, System.currentTimeMillis(), kind)
                    reset()
                }
            }
        } catch (e: Exception) {
            CallEndLog.d("receiver error: $e")
        }
    }

    private companion object {
        @Volatile private var ringing = false
        @Volatile private var answered = false
        @Volatile private var shown = false
        @Volatile private var number: String? = null
        @Volatile private var startMs = System.currentTimeMillis()

        fun reset() {
            ringing = false
            answered = false
            number = null
        }
    }
}
