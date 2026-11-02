package com.photovideo.gallerykz.memories.helper

import android.content.Context

/**
 * What the user dismissed in the For you suggestions, so the same suggestion does not come back: for "Add these to
 * an album" the oldest photo of the period, for "Favorite these best shots" each photo that was shown.
 */
object SuggestionPrefs {

    const val KIND_ALBUM = "album"
    const val KIND_FAVORITES = "favorites"

    private const val FILE = "suggestion_dismissed"
    private const val MAX_KEPT = 2000

    fun dismissed(context: Context, kind: String): Set<Long> =
        prefs(context).getStringSet(kind, null).orEmpty().mapNotNull { it.toLongOrNull() }.toHashSet()

    fun dismiss(context: Context, kind: String, keys: Collection<Long>) {
        val all = prefs(context).getStringSet(kind, null).orEmpty().toMutableSet()
        keys.forEach { all.add(it.toString()) }
        // The list only has to cover what can still be suggested; the oldest entries are dropped when it grows.
        val kept = if (all.size > MAX_KEPT) all.toList().takeLast(MAX_KEPT).toSet() else all
        prefs(context).edit().putStringSet(kind, kept).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
