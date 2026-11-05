package com.photovideo.gallerykz.memories.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import java.text.Collator
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** One installed app that has a launcher icon. */
class LauncherApp(
    val label: String,
    val packageName: String,
    val activityName: String,
    val installTime: Long,
    val isSystem: Boolean,
    val icon: Drawable,
) {
    val component: ComponentName get() = ComponentName(packageName, activityName)
    val key: String get() = "$packageName/$activityName"
}

/**
 * Loads the installed apps in the background and keeps the list in memory, so the app drawer opens with the apps
 * already there. The list is replaced as a whole (never changed in place) and reloaded when an app is installed,
 * removed or updated.
 */
object LauncherApps {

    fun interface Listener {
        fun onAppsChanged(apps: List<LauncherApp>)
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    private var loaded: List<LauncherApp> = emptyList()

    @Volatile
    private var generation = 0

    /** The apps in the order of the saved sort setting; empty until the first load finishes. */
    val apps: List<LauncherApp> get() = loaded

    fun register(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }

    /** Reloads the list off the main thread and tells the listeners on the main thread when it is ready. */
    fun reload(context: Context) {
        val app = context.applicationContext
        val mine = ++generation
        executor.execute {
            val result = try {
                query(app)
            } catch (e: Exception) {
                null
            }
            main.post {
                // A newer reload was started in the meantime: its result is the one to show.
                if (mine != generation || result == null) return@post
                loaded = result
                // The full list now knows every app (icons and labels may have changed with an update).
                quickCache.clear()
                listeners.forEach { it.onAppsChanged(result) }
            }
        }
    }

    /** Re-sorts the loaded list for the current sort setting (no new query needed). */
    fun resort(context: Context) {
        loaded = sort(context, loaded)
        listeners.forEach { it.onAppsChanged(loaded) }
    }

    /** The first loaded app of a package, or null. */
    fun find(packageName: String): LauncherApp? = loaded.firstOrNull { it.packageName == packageName }

    private val quickCache = java.util.concurrent.ConcurrentHashMap<String, LauncherApp>()

    /**
     * Like [find], but it never waits for the full list: while that is still loading (right after the app starts) the one
     * package is looked up on its own. The home screen folders use it, so they are there in the first frame instead of
     * appearing when the whole list of installed apps has been read. The result is the same app the full list holds later.
     */
    fun findNow(context: Context, packageName: String): LauncherApp? {
        find(packageName)?.let { return it }
        quickCache[packageName]?.let { return it }
        val app = lookup(context.applicationContext, packageName) ?: return null
        quickCache[packageName] = app
        return app
    }

    private fun lookup(context: Context, packageName: String): LauncherApp? = try {
        // This app's own icon is the gallery, which is on the home screen already (same rule as the full list).
        if (packageName == context.packageName) {
            null
        } else {
            val pm = context.packageManager
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
            val info = pm.queryIntentActivities(launch, 0).firstOrNull()
            val activity = info?.activityInfo
            if (info == null || activity == null) {
                null
            } else {
                val label = englishLabel(context, info)
                if (label.isBlank()) {
                    null
                } else {
                    val installed = try {
                        pm.getPackageInfo(packageName, 0).firstInstallTime
                    } catch (e: Exception) {
                        0L
                    }
                    val flags = activity.applicationInfo?.flags ?: 0
                    val system = flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    LauncherApp(label, packageName, activity.name, installed, system, info.loadIcon(pm))
                }
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun query(context: Context): List<LauncherApp> {
        val pm = context.packageManager
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val result = ArrayList<LauncherApp>()
        val seen = HashSet<String>()
        for (info in pm.queryIntentActivities(launch, 0)) {
            val activity = info.activityInfo ?: continue
            // This app's own icon is the gallery: it is on the home screen already.
            if (activity.packageName == context.packageName) continue
            // One entry per app, never the same one twice.
            if (!seen.add(activity.packageName)) continue
            val label = try {
                englishLabel(context, info)
            } catch (e: Exception) {
                activity.packageName
            }
            if (label.isBlank()) continue
            val icon = try {
                info.loadIcon(pm)
            } catch (e: Exception) {
                pm.defaultActivityIcon
            }
            val installed = try {
                pm.getPackageInfo(activity.packageName, 0).firstInstallTime
            } catch (e: Exception) {
                0L
            }
            val flags = activity.applicationInfo?.flags ?: 0
            val system = flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            result.add(LauncherApp(label, activity.packageName, activity.name, installed, system, icon))
        }
        return sort(context, result)
    }

    /**
     * The app's name in English whatever language is selected in this app: names of installed apps are never
     * translated by the launcher. Falls back to the phone's own label when the app has no readable English one.
     */
    fun englishLabel(context: Context, info: android.content.pm.ResolveInfo): String {
        val activity = info.activityInfo
        val res = if (activity.labelRes != 0) activity.labelRes else activity.applicationInfo?.labelRes ?: 0
        if (res != 0) {
            try {
                val config = android.content.res.Configuration(context.resources.configuration)
                config.setLocale(java.util.Locale.ENGLISH)
                val text = context.createPackageContext(activity.packageName, 0)
                    .createConfigurationContext(config).resources.getString(res)
                if (text.isNotBlank()) return text
            } catch (e: Exception) {
                // Use the phone's label below.
            }
        }
        return info.loadLabel(context.packageManager).toString()
    }

    /** Same as [englishLabel], for an app that has no activity at hand (its application label). */
    fun englishAppLabel(context: Context, info: ApplicationInfo): String {
        if (info.labelRes != 0) {
            try {
                val config = android.content.res.Configuration(context.resources.configuration)
                config.setLocale(java.util.Locale.ENGLISH)
                val text = context.createPackageContext(info.packageName, 0)
                    .createConfigurationContext(config).resources.getString(info.labelRes)
                if (text.isNotBlank()) return text
            } catch (e: Exception) {
                // Use the phone's label below.
            }
        }
        return context.packageManager.getApplicationLabel(info).toString()
    }

    fun sort(context: Context, list: List<LauncherApp>): List<LauncherApp> {
        val collator = Collator.getInstance()
        return when (LauncherSettings.getSort(context)) {
            LauncherSettings.SORT_NAME_DESC -> list.sortedWith { a, b -> collator.compare(b.label, a.label) }
            LauncherSettings.SORT_INSTALLED -> list.sortedWith(
                compareByDescending<LauncherApp> { it.installTime }.thenBy { it.label.lowercase() },
            )
            else -> list.sortedWith { a, b -> collator.compare(a.label, b.label) }
        }
    }

    /** Starts an app; false when it can no longer be opened (removed, disabled). */
    fun launch(context: Context, app: LauncherApp): Boolean = try {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
        RecentAppsStore.record(context, app.packageName)
        true
    } catch (e: Exception) {
        false
    }

    /** Whether the user may uninstall the app from the drawer's long-press menu. */
    fun canUninstall(context: Context, app: LauncherApp): Boolean =
        (!app.isSystem) && app.packageName != context.packageName
}
