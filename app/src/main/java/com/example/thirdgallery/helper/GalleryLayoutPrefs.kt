package com.example.thirdgallery.helper

import android.content.Context
import com.example.thirdgallery.model.GalleryLayout
import com.example.thirdgallery.model.TimelineZoom

/** Persists the Gallery layout choices: layout, Timeline zoom level and grid column count. */
object GalleryLayoutPrefs {

    private const val FILE = "gallery_prefs"
    private const val KEY_LAYOUT = "gallery_layout"
    private const val KEY_ZOOM = "gallery_timeline_zoom"
    private const val KEY_COLUMNS = "gallery_columns"

    const val MIN_COLUMNS = 2
    const val MAX_COLUMNS = 5
    const val DEFAULT_COLUMNS = 3

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getLayout(context: Context): GalleryLayout = try {
        GalleryLayout.fromKey(prefs(context).getString(KEY_LAYOUT, null))
    } catch (e: Exception) {
        GalleryLayout.CLASSIC
    }

    fun getZoom(context: Context): TimelineZoom = try {
        TimelineZoom.fromKey(prefs(context).getString(KEY_ZOOM, null))
    } catch (e: Exception) {
        TimelineZoom.DAYS
    }

    fun getColumns(context: Context): Int = try {
        prefs(context).getInt(KEY_COLUMNS, DEFAULT_COLUMNS).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    } catch (e: Exception) {
        DEFAULT_COLUMNS
    }

    fun setLayout(context: Context, layout: GalleryLayout) = save(context) { putString(KEY_LAYOUT, layout.key) }

    fun setZoom(context: Context, zoom: TimelineZoom) = save(context) { putString(KEY_ZOOM, zoom.key) }

    fun setColumns(context: Context, columns: Int) = save(context) { putInt(KEY_COLUMNS, columns) }

    private fun save(context: Context, block: android.content.SharedPreferences.Editor.() -> Unit) {
        try {
            prefs(context).edit().apply(block).apply()
        } catch (e: Exception) {
            // Best effort: the choice still applies for this session.
        }
    }
}
