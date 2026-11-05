package com.photovideo.gallerykz.memories.launcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.ads.AdGate
import com.photovideo.gallerykz.memories.ads.AdRemoteConfig
import com.photovideo.gallerykz.memories.helper.OverlayPermission

/**
 * The two popups of the launcher's right swipe (Home to the gallery), both from the Remote Config:
 *  1. "Make this the Home app" (`Default_App_Popup_Show` / `Default_App_Popup_Count`): the role dialog.
 *  2. "Display over other apps" (`Phone_Overlay_Popup_Show` / `Phone_Overlay_Popup_Count`): a bottom sheet, then
 *     READ_PHONE_STATE, then the overlay settings.
 * The overlay popup is only tried when the Home popup did not show. They start only from [onExplicitEntry] (the user's swipe or
 * tap), never from onResume, so coming back from a settings page cannot fire them again. Create it as a property of the activity
 * (its launchers are registered at construction).
 */
class LauncherPopups(
    private val activity: ComponentActivity,
    private val defaultHome: DefaultHomeRequest,
    /** True only while the gallery page is the settled, visible page. */
    private val isGalleryVisible: () -> Boolean,
) {

    private var defaultActive = false
    private var overlayFlow = false
    private var overlaySettingsOpen = false
    private var phoneStateInProgress = false
    private var sheet: BottomSheetDialog? = null

    private val handler = Handler(Looper.getMainLooper())

    /** The moment the overlay permission is granted the app is brought back to the front, from the settings page. */
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!overlaySettingsOpen || activity.isFinishing || activity.isDestroyed) return
            if (OverlayPermission.isGranted(activity)) {
                try {
                    activity.startActivity(
                        Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                } catch (e: Exception) {
                    // The system refused to start it from the settings page; onResume finishes the flow.
                }
                return
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val phoneStateLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        phoneStateInProgress = false
        openOverlaySettings()
    }

    private val overlaySettingsLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        overlaySettingsOpen = false
        handler.removeCallbacks(pollRunnable)
        finishOverlayFlow()
    }

    /** True while one of the popups (or the system screen it opened) is up: nothing else (the swipe ad) should cover it. */
    val isActive: Boolean get() = defaultActive || overlayFlow

    /**
     * The user swiped or tapped to the gallery. Starts the Home popup, else the overlay popup.
     * @return true when a popup started (the swipe ad then yields).
     */
    fun onExplicitEntry(): Boolean {
        if (isActive) return true
        if (activity.isFinishing || activity.isDestroyed) return false
        // One popup per swipe, never back to back: after any popup the next one waits the shorter of the two intervals.
        // The popup shown longer ago goes first, so with equal counts they alternate instead of one starving the other.
        if (!sharedGapElapsed()) return false
        val defaultFirst = AdGate.lastShown(activity, KEY_DEFAULT) <= AdGate.lastShown(activity, KEY_OVERLAY)
        return if (defaultFirst) maybeShowDefaultHome() || maybeShowOverlay() else maybeShowOverlay() || maybeShowDefaultHome()
    }

    private fun sharedGapElapsed(): Boolean {
        val c = AdRemoteConfig.config
        if (c.defaultPopupPerDay <= 0 || c.overlayPopupPerDay <= 0) return true
        val gap = DAY_MS / maxOf(c.defaultPopupPerDay, c.overlayPopupPerDay)
        val last = maxOf(AdGate.lastShown(activity, KEY_DEFAULT), AdGate.lastShown(activity, KEY_OVERLAY))
        return System.currentTimeMillis() - last >= gap
    }

    fun onResume() {
        if (overlayFlow && OverlayPermission.isGranted(activity)) finishOverlayFlow()
    }

    fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        sheet?.setOnDismissListener(null)
        sheet?.dismiss()
        sheet = null
    }

    /** Called when the Home role / settings attempt is over. */
    fun onDefaultResult() {
        defaultActive = false
    }

    // ---- 1. Home app ----

    private fun maybeShowDefaultHome(): Boolean {
        val c = AdRemoteConfig.config
        if (!c.defaultPopupShow || !isGalleryVisible()) return false
        if (LauncherNav.isHomeAppOrRoleHolder(activity)) return false
        if (!AdGate.intervalElapsed(activity, KEY_DEFAULT, c.defaultPopupPerDay, zeroIsAlways = true)) return false
        if (LauncherNav.defaultHomeIntent(activity) == null) return false

        AdGate.markShown(activity, KEY_DEFAULT)
        defaultActive = true
        defaultHome.request()
        // The request did not start (it toasts the failure itself): nothing is open, so nothing is pending.
        if (!defaultHome.isInFlight) defaultActive = false
        return defaultActive
    }

    // ---- 2. Display over other apps ----

    private fun maybeShowOverlay(): Boolean {
        val c = AdRemoteConfig.config
        if (!c.overlayPopupShow || !isGalleryVisible()) return false
        if (LauncherNav.isHomeAppOrRoleHolder(activity) || OverlayPermission.isGranted(activity)) return false
        if (!AdGate.intervalElapsed(activity, KEY_OVERLAY, c.overlayPopupPerDay, zeroIsAlways = true)) return false

        AdGate.markShown(activity, KEY_OVERLAY)
        overlayFlow = true
        showSheet()
        return true
    }

    private fun showSheet() {
        val dialog = BottomSheetDialog(activity)
        dialog.window?.setBackgroundDrawable(ColorDrawable(0))
        dialog.setContentView(R.layout.dialog_phone_overlay_permission)
        dialog.setCancelable(false)
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(0)
        var allowed = false
        dialog.findViewById<View>(R.id.ivClose)?.setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.btnAllow)?.setOnClickListener {
            allowed = true
            dialog.dismiss()
            requestPhoneStateThenOverlay()
        }
        dialog.setOnDismissListener {
            sheet = null
            if (!allowed) finishOverlayFlow()
        }
        sheet = dialog
        dialog.show()
    }

    private fun finishOverlayFlow() {
        phoneStateInProgress = false
        overlayFlow = false
    }

    private fun requestPhoneStateThenOverlay() {
        if (activity.isFinishing || activity.isDestroyed) {
            finishOverlayFlow()
            return
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            openOverlaySettings()
        } else if (!phoneStateInProgress) {
            phoneStateInProgress = true
            phoneStateLauncher.launch(Manifest.permission.READ_PHONE_STATE)
        }
    }

    private fun openOverlaySettings() {
        if (activity.isFinishing || activity.isDestroyed) {
            finishOverlayFlow()
            return
        }
        if (OverlayPermission.isGranted(activity)) {
            finishOverlayFlow()
            return
        }
        if (overlaySettingsOpen) return
        try {
            overlaySettingsOpen = true
            overlaySettingsLauncher.launch(OverlayPermission.settingsIntent(activity))
            handler.removeCallbacks(pollRunnable)
            handler.post(pollRunnable)
        } catch (e: Exception) {
            overlaySettingsOpen = false
            finishOverlayFlow()
        }
    }

    private companion object {
        const val KEY_DEFAULT = "default_app_popup"
        const val KEY_OVERLAY = "phone_overlay_popup"
        const val POLL_MS = 300L
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
