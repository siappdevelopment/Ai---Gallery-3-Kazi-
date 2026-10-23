package com.example.thirdgallery.helper

import android.content.Context

/** The most recent searches (newest first, no duplicates), kept in SharedPreferences. */
object SearchHistoryPrefs {

    private const val FILE = "search_history"
    private const val KEY = "recent"
    private const val SEPARATOR = '\u0001'
    private const val MAX_ITEMS = 10

    fun get(context: Context): List<String> {
        val raw = prefs(context).getString(KEY, null).orEmpty()
        return if (raw.isEmpty()) emptyList() else raw.split(SEPARATOR).filter { it.isNotBlank() }
    }

    /** Puts [query] first (a repeated search moves up instead of being listed twice) and returns the new list. */
    fun add(context: Context, query: String): List<String> {
        val text = query.trim()
        if (text.isEmpty()) return get(context)
        val next = (listOf(text) + get(context).filterNot { it.equals(text, ignoreCase = true) }).take(MAX_ITEMS)
        prefs(context).edit().putString(KEY, next.joinToString(SEPARATOR.toString())).apply()
        return next
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
