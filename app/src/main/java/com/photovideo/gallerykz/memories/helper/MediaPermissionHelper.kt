package com.photovideo.gallerykz.memories.helper

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

    private const val PREFS = "permission_asks"
    private const val KEY_NOTI_ASKED_AT_SPLASH = "noti_asked_at_splash"

    /** The splash asks for the notification permission only on the first start; the gallery page asks again later when it is still missing. */
    fun wasNotificationAskedAtSplash(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NOTI_ASKED_AT_SPLASH, false)
    } catch (e: Exception) {
        true
    }

    fun markNotificationAskedAtSplash(context: Context) {
        try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NOTI_ASKED_AT_SPLASH, true).commit()
        } catch (e: Exception) {
            // Best effort.
        }
    }

    private const val KEY_MEDIA_ASKED = "media_asked"

    /** True once the photo / video question was put to the user (stored; cleared with the app data). */
    fun wasMediaAsked(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MEDIA_ASKED, false)
    } catch (e: Exception) {
        true
    }

    fun markMediaAsked(context: Context) {
        try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_MEDIA_ASKED, true).commit()
        } catch (e: Exception) {
            // Best effort.
        }
    }

    /**
     * True on the very first entry to the gallery page (first start or after clearing the app data): the access is missing and the
     * dialog was never shown, so it is about to open and the swipe that leads there must not show an ad.
     */
    fun firstMediaFlowPending(context: Context): Boolean = !wasMediaAsked(context) && !hasAccess(context)

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
