package com.example.thirdgallery.helper

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Photo/video runtime permission rules for the different Android versions. */
object MediaPermissionHelper {

    /**
     * Permissions to request together:
     * - API 34+: images + video + "selected photos" so the system offers Allow all / Select photos.
     * - API 33: images + video.
     * - older: READ_EXTERNAL_STORAGE.
     */
    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** True for full access and, on API 34+, also for partial ("selected photos") access. */
    fun hasAccess(context: Context): Boolean {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return try {
            when {
                Build.VERSION.SDK_INT >= 34 ->
                    (granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)) ||
                        granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                Build.VERSION.SDK_INT >= 33 ->
                    granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO)
                else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Notifications need a runtime permission only from API 33. */
    fun needsNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    /** Opens this app's page in system Settings. Returns false if no Settings screen could open. */
    fun openAppSettings(activity: Activity): Boolean {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", activity.packageName, null))
        return try {
            activity.startActivity(details)
            true
        } catch (e: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
                true
            } catch (e2: Exception) {
                false
            }
        }
    }
}
