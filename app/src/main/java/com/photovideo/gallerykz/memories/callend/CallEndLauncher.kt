package com.photovideo.gallerykz.memories.callend

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Builds the call summary intent and opens it the way the phone allows: straight away, or through a lock-screen notification. */
object CallEndLauncher {

    fun intent(context: Context, number: String?, startMs: Long, endMs: Long, kind: CallKind): Intent =
        Intent(context, CallEndActivity::class.java)
            .putExtra(CallEndActivity.EXTRA_NUMBER, number?.trim().orEmpty())
            .putExtra(CallEndActivity.EXTRA_START, startMs)
            .putExtra(CallEndActivity.EXTRA_END, endMs)
            .putExtra(CallEndActivity.EXTRA_KIND, kind.key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /**
     * Opens the summary after a call. An unlocked phone with "display over other apps" opens it at once (the only way a
     * background app may start a screen); otherwise a full-screen notification carries it, which also works on the lock screen.
     */
    fun openAfterCall(context: Context, number: String?, startMs: Long, endMs: Long, kind: CallKind) {
        val app = context.applicationContext
        val intent = intent(app, number, startMs, endMs, kind)
        val locked = (app.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked ?: false
        val canOverlay = try {
            Settings.canDrawOverlays(app)
        } catch (e: Exception) {
            false
        }
        CallEndLog.d("openAfterCall locked=$locked canOverlay=$canOverlay")
        if (!locked && canOverlay) {
            try {
                app.startActivity(intent)
                CallEndLog.d("opened the screen directly")
                return
            } catch (e: Exception) {
                CallEndLog.d("direct start failed: $e")
                // Fall back to the notification.
            }
        }
        CallEndLog.d("posting the full-screen notification")
        CallEndNotifier.post(app, intent)
    }
}
