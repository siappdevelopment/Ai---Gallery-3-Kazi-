package com.photovideo.gallerykz.memories.helper

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

    /** The permission is only asked for while this app is not the Home app: the Home app may start its screens from the background without it. */
    fun isNeeded(context: Context?): Boolean =
        context != null && !isGranted(context) && !com.photovideo.gallerykz.memories.launcher.LauncherNav.isHomeAppOrRoleHolder(context)

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
}
