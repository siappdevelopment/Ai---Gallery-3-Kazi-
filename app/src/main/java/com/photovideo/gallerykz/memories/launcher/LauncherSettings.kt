package com.photovideo.gallerykz.memories.launcher

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The launcher's own settings (sort order, app name visibility, icon and name size), kept in SharedPreferences. Every
 * setter notifies the registered listeners, so the home screen and the open app drawer update while the settings
 * screen is still in front.
 */
object LauncherSettings {

    const val SORT_NAME_ASC = "a"
    const val SORT_NAME_DESC = "d"
    const val SORT_INSTALLED = "i"

    const val ICON_MIN = 40
    const val ICON_MAX = 80
    const val ICON_DEFAULT = 60
    const val LABEL_MIN = 10
    const val LABEL_MAX = 16
    const val LABEL_DEFAULT = 12

    private const val FILE = "launcher_prefs"
    private const val KEY_SORT = "app_sort"
    private const val KEY_LABELS = "label_visible"
    private const val KEY_ICON = "icon_size"
    private const val KEY_LABEL = "label_size"

    fun interface Listener {
        fun onLauncherSettingsChanged()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun register(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getSort(context: Context): String = try {
        prefs(context).getString(KEY_SORT, SORT_NAME_ASC)
            ?.takeIf { it == SORT_NAME_ASC || it == SORT_NAME_DESC || it == SORT_INSTALLED }
            ?: SORT_NAME_ASC
    } catch (e: Exception) {
        SORT_NAME_ASC
    }

    fun setSort(context: Context, sort: String) = save(context) { putString(KEY_SORT, sort) }

    fun isLabelVisible(context: Context): Boolean = try {
        prefs(context).getBoolean(KEY_LABELS, true)
    } catch (e: Exception) {
        true
    }

    fun setLabelVisible(context: Context, visible: Boolean) = save(context) { putBoolean(KEY_LABELS, visible) }

    fun getIconSize(context: Context): Int = try {
        prefs(context).getInt(KEY_ICON, ICON_DEFAULT).coerceIn(ICON_MIN, ICON_MAX)
    } catch (e: Exception) {
        ICON_DEFAULT
    }

    fun setIconSize(context: Context, dp: Int) = save(context) { putInt(KEY_ICON, dp.coerceIn(ICON_MIN, ICON_MAX)) }

    fun getLabelSize(context: Context): Int = try {
        prefs(context).getInt(KEY_LABEL, LABEL_DEFAULT).coerceIn(LABEL_MIN, LABEL_MAX)
    } catch (e: Exception) {
        LABEL_DEFAULT
    }

    fun setLabelSize(context: Context, sp: Int) = save(context) { putInt(KEY_LABEL, sp.coerceIn(LABEL_MIN, LABEL_MAX)) }

    /** The icon size in pixels. */
    fun iconPx(context: Context): Int = (getIconSize(context) * context.resources.displayMetrics.density).toInt()

    /** Icons per row on the home screen (top row, dock), in the settings preview and in the app drawer. */
    const val ROW_COLUMNS = 4

    /** The room that always stays free between two icons of a row, and the smallest icon a narrow row may shrink to. */
    private const val MIN_GAP_DP = 14
    private const val FLOOR_DP = 36

    /**
     * The chosen icon size, but never so big that [columns] icons no longer fit in [rowWidthPx] with [MIN_GAP_DP] between them.
     * The chosen size is in dp, so on a narrow phone the biggest sizes would otherwise touch or overlap; this keeps the
     * spacing right on every screen width. An unknown width ([rowWidthPx] <= 0) leaves the chosen size as it is.
     */
    fun fittedIconPx(context: Context, rowWidthPx: Int, columns: Int = ROW_COLUMNS, iconDp: Int = getIconSize(context)): Int {
        val density = context.resources.displayMetrics.density
        val wanted = (iconDp * density).toInt()
        if (rowWidthPx <= 0 || columns <= 0) return wanted
        val room = rowWidthPx / columns - (MIN_GAP_DP * density).toInt()
        return minOf(wanted, maxOf(room, (FLOOR_DP * density).toInt()))
    }

    private fun save(context: Context, write: SharedPreferences.Editor.() -> Unit) {
        try {
            prefs(context).edit().apply(write).apply()
        } catch (e: Exception) {
            // Best effort: the listeners still refresh the screens for this session.
        }
        listeners.forEach { it.onLauncherSettingsChanged() }
    }
}
