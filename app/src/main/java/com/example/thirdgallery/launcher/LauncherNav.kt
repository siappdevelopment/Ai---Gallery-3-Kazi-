package com.example.thirdgallery.launcher

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.example.thirdgallery.activity.HomeActivity
import com.example.thirdgallery.activity.SplashActivity
import com.example.thirdgallery.helper.OnboardingPrefs

/** Small helpers shared by the launcher screens, widgets and the Recent page. */
object LauncherNav {

    /** Extra of the launcher's HOME intent: scroll to the gallery page. */
    const val EXTRA_OPEN_GALLERY = "launcher_open_gallery"

    /**
     * Where a widget or another screen sends the user to see the gallery: the launcher home, which scrolls to the
     * gallery page. Before the onboarding is finished the splash flow runs first and ends in the launcher.
     */
    fun galleryIntent(context: Context): Intent {
        val done = OnboardingPrefs.getStep(context) == OnboardingPrefs.STEP_DONE
        return if (done) {
            Intent(context, LauncherHomeActivity::class.java)
                .putExtra(EXTRA_OPEN_GALLERY, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(context, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** The stand-alone gallery screen (kept for places that cannot go through the launcher). */
    fun standaloneGalleryIntent(context: Context): Intent = Intent(context, HomeActivity::class.java)

    /** True while this app is the phone's selected Home app. */
    fun isDefaultHome(context: Context): Boolean = try {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == context.packageName
    } catch (e: Exception) {
        false
    }

    /** True when this app is the Home app or holds the Home role (the role can be held a moment before the resolver agrees). */
    fun isHomeAppOrRoleHolder(context: Context): Boolean {
        if (isDefaultHome(context)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            return try {
                roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && roles.isRoleHeld(RoleManager.ROLE_HOME)
            } catch (e: Exception) {
                false
            }
        }
        return false
    }

    /**
     * The intent that asks Android to make this app the Home app: the role dialog from Android 10, the system's Home
     * app settings before. Null when the phone offers neither.
     */
    fun defaultHomeIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                return roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
            }
        }
        return homeSettingsIntent(context)
    }

    /** The system's Home app settings page (also the fallback when the role dialog is silently skipped). */
    fun homeSettingsIntent(context: Context): Intent? {
        val settings = Intent(Settings.ACTION_HOME_SETTINGS)
        if (settings.resolveActivity(context.packageManager) != null) return settings
        val fallback = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        return if (fallback.resolveActivity(context.packageManager) != null) fallback else null
    }
}
