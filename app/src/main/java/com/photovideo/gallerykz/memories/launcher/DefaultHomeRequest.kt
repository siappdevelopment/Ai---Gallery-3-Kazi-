package com.photovideo.gallerykz.memories.launcher

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.photovideo.gallerykz.memories.R

/**
 * Asks Android to make this app the Home app: the role dialog from Android 10, the Home settings before. One instance
 * per activity (create it as a property so its launcher is registered in time); [onResult] runs when an attempt is over,
 * and the answer itself is never trusted, the caller reads the state with [LauncherNav]. A role dialog that closes
 * almost at once was skipped by the system, so the Home settings are opened instead. Repeated taps while a request
 * is open are ignored.
 */
class DefaultHomeRequest(private val activity: ComponentActivity, private val onResult: () -> Unit) {

    private var startedAt = 0L
    private var isSettings = false
    private var inFlight = false

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { handleResult() }

    fun request() {
        if (inFlight) return
        val intent = LauncherNav.defaultHomeIntent(activity)
        if (intent == null) {
            Toast.makeText(activity.applicationContext, R.string.settings_open_failed, Toast.LENGTH_SHORT).show()
            return
        }
        isSettings = intent.action != null && intent.action != ROLE_ACTION
        startedAt = SystemClock.elapsedRealtime()
        inFlight = true
        try {
            launcher.launch(intent)
            // The Home settings list gets the tutorial card over it (the role dialog explains itself).
            if (isSettings) DefaultHomeGuideActivity.show(activity)
        } catch (e: Exception) {
            inFlight = false
            Toast.makeText(activity.applicationContext, R.string.settings_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Opens the system's Home app screen straight away (the onboarding does this once, before it explains the choice). */
    /** True when the system screen was really started (the caller's screen is about to be left). */
    fun requestSettings(): Boolean {
        if (inFlight) return false
        val intent = LauncherNav.homeSettingsIntent(activity) ?: return false
        isSettings = true
        startedAt = SystemClock.elapsedRealtime()
        inFlight = true
        try {
            launcher.launch(intent)
            DefaultHomeGuideActivity.show(activity)
            return true
        } catch (e: Exception) {
            inFlight = false
            return false
        }
    }

    private fun handleResult() {
        inFlight = false
        // A role dialog that closes almost at once was skipped by the system: the Home settings continue the attempt.
        if (!LauncherNav.isHomeAppOrRoleHolder(activity) && !isSettings && SystemClock.elapsedRealtime() - startedAt < SILENT_CANCEL_MS) {
            val settings = LauncherNav.homeSettingsIntent(activity)
            if (settings != null) {
                isSettings = true
                startedAt = SystemClock.elapsedRealtime()
                inFlight = true
                try {
                    launcher.launch(settings)
                    DefaultHomeGuideActivity.show(activity)
                    return
                } catch (e: Exception) {
                    inFlight = false
                }
            }
        }
        onResult()
    }

    private companion object {
        const val ROLE_ACTION = "android.app.role.action.REQUEST_ROLE"
        const val SILENT_CANCEL_MS = 800L
    }
}
