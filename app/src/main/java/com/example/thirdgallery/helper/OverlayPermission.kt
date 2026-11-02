package com.example.thirdgallery.helper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** "Display over other apps": the state (always read from the system) and the settings page of this app. */
object OverlayPermission {

    fun isGranted(context: Context?): Boolean = try {
        context != null && Settings.canDrawOverlays(context)
    } catch (e: Exception) {
        false
    }

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
}
