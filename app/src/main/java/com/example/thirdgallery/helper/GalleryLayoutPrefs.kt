package com.example.thirdgallery.helper

import android.content.Context
import com.example.thirdgallery.model.GalleryLayout

/** Persists the layout chosen in the Gallery layout picker. */
object GalleryLayoutPrefs {

    private const val FILE = "gallery_prefs"
    private const val KEY_LAYOUT = "gallery_layout"

    fun get(context: Context): GalleryLayout = try {
        GalleryLayout.fromKey(
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LAYOUT, null)
        )
    } catch (e: Exception) {
        GalleryLayout.CLASSIC
    }

    fun set(context: Context, layout: GalleryLayout) {
        try {
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAYOUT, layout.key).apply()
        } catch (e: Exception) {
            // Best effort: the choice still applies for this session.
        }
    }
}
