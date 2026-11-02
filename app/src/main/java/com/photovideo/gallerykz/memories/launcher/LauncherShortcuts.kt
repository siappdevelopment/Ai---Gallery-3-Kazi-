package com.photovideo.gallerykz.memories.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import android.net.Uri

/** An app the home screen can open: its icon, label, the intent to start it and its package (for the Recent page). */
class ShortcutTarget(val icon: Drawable, val label: String, val intent: Intent, val packageName: String)

/**
 * Finds the apps behind the dock and the top shortcuts from what the phone really has: the default dialer and SMS app,
 * Chrome or the default browser, the camera, and the system Contacts and Calendar. Nothing is hard-coded to one
 * brand, and a chooser or resolver activity is never started: a missing app simply hides its slot.
 */
object LauncherShortcuts {

    const val GOOGLE_APP = "com.google.android.googlequicksearchbox"

    private const val CHROME = "com.android.chrome"

    /** Folder 1: shown when installed, in this order. */
    val GOOGLE_PACKAGES = listOf(
        CHROME,
        "com.google.android.apps.docs.editors.docs",
        "com.google.android.gm",
        GOOGLE_APP,
        "com.google.android.apps.maps",
        "com.google.android.apps.photos",
        "com.google.android.youtube",
        "com.android.vending",
        "com.google.android.calendar",
    )

    /** Folder 2: one app per group, the first installed one wins. */
    val TOOLS_GROUPS = listOf(
        listOf("com.google.android.calculator", "com.sec.android.app.popupcalculator", "com.miui.calculator", "com.android.calculator2"),
        listOf("com.google.android.deskclock", "com.sec.android.app.clockpackage", "com.android.deskclock"),
        listOf("com.google.android.keep", "com.miui.notes", "com.samsung.android.app.notes"),
        listOf("com.google.android.calendar", "com.android.calendar"),
        listOf("com.google.android.documentsui", "com.android.documentsui", "com.mi.android.globalFileexplorer", "com.sec.android.app.myfiles", "com.coloros.filemanager"),
        listOf("com.android.settings"),
    )

    fun dialer(context: Context): ShortcutTarget? = try {
        val pkg = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        pkg?.let { forPackage(context, it, Intent(Intent.ACTION_DIAL)) }
    } catch (e: Exception) {
        null
    }

    fun sms(context: Context): ShortcutTarget? = try {
        val pkg = Telephony.Sms.getDefaultSmsPackage(context)
            ?: resolveCategory(context, Intent.CATEGORY_APP_MESSAGING)?.activityInfo?.packageName
        pkg?.let { forPackage(context, it, null) }
    } catch (e: Exception) {
        null
    }

    fun browser(context: Context): ShortcutTarget? = try {
        val pm = context.packageManager
        val view = Intent(Intent.ACTION_VIEW, Uri.parse("http://www.google.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val pkg = if (pm.getLaunchIntentForPackage(CHROME) != null) CHROME else concrete(pm, view)?.activityInfo?.packageName
        pkg?.let { forPackage(context, it, view) }
    } catch (e: Exception) {
        null
    }

    fun camera(context: Context): ShortcutTarget? = try {
        val pm = context.packageManager
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        val info = concrete(pm, intent)
        info?.let {
            val pkg = it.activityInfo.packageName
            ShortcutTarget(it.loadIcon(pm), it.loadLabel(pm).toString(), Intent(intent).setPackage(pkg), pkg)
        }
    } catch (e: Exception) {
        null
    }

    /** The system Contacts app (never one the user installed). */
    fun contacts(context: Context): ShortcutTarget? = systemApp(context, Intent.CATEGORY_APP_CONTACTS)

    /** The system Calendar app (never one the user installed). */
    fun calendar(context: Context): ShortcutTarget? = systemApp(context, Intent.CATEGORY_APP_CALENDAR)

    private fun systemApp(context: Context, category: String): ShortcutTarget? = try {
        val pm = context.packageManager
        val main = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category)
        val preferred = pm.resolveActivity(main, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        val info = pm.queryIntentActivities(main, 0)
            .filter { isConcrete(it) && isSystem(it) }
            .sortedWith(compareBy<ResolveInfo> { it.activityInfo.packageName != preferred }.thenBy { it.activityInfo.packageName })
            .firstOrNull()
        info?.let {
            val component = android.content.ComponentName(it.activityInfo.packageName, it.activityInfo.name)
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category).setComponent(component)
            ShortcutTarget(it.loadIcon(pm), it.loadLabel(pm).toString(), intent, it.activityInfo.packageName)
        }
    } catch (e: Exception) {
        null
    }

    private fun forPackage(context: Context, pkg: String, fallback: Intent?): ShortcutTarget? {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(pkg) ?: fallback?.let { Intent(it).setPackage(pkg) } ?: return null
        val info = pm.getApplicationInfo(pkg, 0)
        return ShortcutTarget(pm.getApplicationIcon(pkg), pm.getApplicationLabel(info).toString(), intent, pkg)
    }

    private fun resolveCategory(context: Context, category: String): ResolveInfo? =
        concrete(context.packageManager, Intent(Intent.ACTION_MAIN).addCategory(category))

    /** The app Android would open for the intent; with no default chosen, the first real app that can. */
    private fun concrete(pm: PackageManager, intent: Intent): ResolveInfo? {
        val default = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (default != null && isConcrete(default)) return default
        return pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).firstOrNull { isConcrete(it) }
    }

    private fun isConcrete(info: ResolveInfo): Boolean {
        val activity = info.activityInfo ?: return false
        return activity.packageName != "android" &&
            !activity.name.endsWith("ResolverActivity") &&
            !activity.name.endsWith("ChooserActivity")
    }

    private fun isSystem(info: ResolveInfo): Boolean {
        val flags = info.activityInfo?.applicationInfo?.flags ?: return false
        return flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }

    /** The Google app's voice search, the voice command screen, or any web search: the first that exists. */
    fun voiceSearchIntents(): List<Intent> = listOf(
        Intent(android.speech.RecognizerIntent.ACTION_WEB_SEARCH).setPackage(GOOGLE_APP),
        Intent(Intent.ACTION_VOICE_COMMAND),
        Intent(android.speech.RecognizerIntent.ACTION_WEB_SEARCH),
    )
}
