package com.photovideo.gallerykz.memories.launcher

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import com.photovideo.gallerykz.memories.R

/**
 * Xiaomi, Redmi and POCO phones (MIUI / HyperOS) have a permission of their own, "Display pop-up windows while running in the
 * background", that Android does not know. Without it the system refuses every screen this app opens while another screen (the
 * phone's own Recents overview) is in front, and the Recent page then never shows. This file checks that permission and takes the
 * user to the page where it is switched on.
 */
object MiuiBackgroundStart {

    /** The MIUI app-op number of "start activities in the background" (the same on MIUI 12 up to HyperOS). */
    private const val OP_BACKGROUND_START = 10021

    private const val PREFS = "launcher_miui"
    private const val KEY_ASKED_AT = "bg_start_asked_at"
    private const val ASK_AGAIN_MS = 3L * 24 * 60 * 60 * 1000

    fun isXiaomiFamily(): Boolean {
        val maker = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        return maker == "xiaomi" || brand == "xiaomi" || brand == "redmi" || brand == "poco"
    }

    /**
     * True when the phone allows this app to start screens from the background. Phones that are not Xiaomi family, and any phone where the
     * answer cannot be read, count as "allowed": nothing is asked there.
     */
    fun isAllowed(context: Context): Boolean {
        if (!isXiaomiFamily()) return true
        return try {
            val manager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val method = AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
            val mode = method.invoke(manager, OP_BACKGROUND_START, Process.myUid(), context.packageName) as Int
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            true
        }
    }

    /** Opens the phone's permission page of this app; false when nothing could be opened. */
    fun openSettings(context: Context): Boolean {
        val attempts = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                .putExtra("extra_pkgname", context.packageName),
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setPackage("com.miui.securitycenter")
                .putExtra("extra_pkgname", context.packageName),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
        for (intent in attempts) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: Exception) {
                // The next way.
            }
        }
        return false
    }

    /**
     * Asks once in a while (on the launcher home, while the screen is in front) for the permission, with the reason in plain words and a
     * button to the right settings page. Only on a Xiaomi family phone where this app is the Home app and the permission is off.
     */
    fun askIfNeeded(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!isXiaomiFamily() || isAllowed(activity) || !LauncherNav.isHomeAppOrRoleHolder(activity)) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_ASKED_AT, 0L) < ASK_AGAIN_MS) return
        prefs.edit().putLong(KEY_ASKED_AT, now).apply()
        AlertDialog.Builder(activity)
            .setTitle(R.string.miui_recent_title)
            .setMessage(R.string.miui_recent_message)
            .setPositiveButton(R.string.miui_recent_open) { _, _ -> openSettings(activity) }
            .setNegativeButton(R.string.miui_recent_later, null)
            .show()
    }
}
