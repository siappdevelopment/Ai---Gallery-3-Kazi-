package com.example.thirdgallery.helper

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore

/**
 * What the last cleanup removed (the Cleaning report page of the launcher): the number of items and their size. A cleanup is
 * "Keep selected" of Best from bursts, which moves the other shots of a burst to the Bin. The newest cleanup replaces the
 * previous one; nothing is stored until one has happened.
 */
object CleaningReportStore {

    class Report(val bytes: Long, val items: Int, val atMs: Long)

    private const val FILE = "cleaning_report"
    private const val KEY_BYTES = "bytes"
    private const val KEY_ITEMS = "items"
    private const val KEY_AT = "at_ms"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun record(context: Context, bytes: Long, items: Int) {
        if (items <= 0) return
        try {
            prefs(context).edit()
                .putLong(KEY_BYTES, bytes.coerceAtLeast(0L))
                .putInt(KEY_ITEMS, items)
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            // The report is a nicety; a failed write must never break the cleanup itself.
        }
    }

    /** The last cleanup, or null when nothing was cleaned yet. */
    fun load(context: Context): Report? = try {
        val p = prefs(context)
        if (p.contains(KEY_ITEMS)) Report(p.getLong(KEY_BYTES, 0L), p.getInt(KEY_ITEMS, 0), p.getLong(KEY_AT, 0L)) else null
    } catch (e: Exception) {
        null
    }

    fun register(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        try {
            prefs(context).registerOnSharedPreferenceChangeListener(listener)
        } catch (e: Exception) {
            // No live updates then; the page still reads the report when it is shown.
        }
    }

    fun unregister(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        try {
            prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
        } catch (e: Exception) {
            // Already gone.
        }
    }

    /** The total size of the files behind [uris] (those that cannot be read count as 0). Blocking: call it off the main thread. */
    fun sizeOf(resolver: ContentResolver, uris: List<Uri>): Long {
        var total = 0L
        for (uri in uris) {
            try {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) total += c.getLong(0)
                }
            } catch (e: Exception) {
                // Skip this one.
            }
        }
        return total
    }
}
