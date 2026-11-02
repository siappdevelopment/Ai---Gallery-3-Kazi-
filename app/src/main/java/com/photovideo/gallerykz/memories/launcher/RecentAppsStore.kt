package com.photovideo.gallerykz.memories.launcher

import android.content.Context

/**
 * The apps opened from the launcher (drawer, dock, folders, calendar), newest first, for the Recent page. Stored as
 * lines "package|time" in SharedPreferences; the cached list never touches PackageManager.
 */
object RecentAppsStore {

    private const val FILE = "launcher_recent_apps"
    private const val KEY = "entries"
    private const val MAX_STORED = 30

    @Volatile
    private var cache: List<String>? = null

    /** Package names, newest first. */
    fun packages(context: Context): List<String> {
        cache?.let { return it }
        val list = try {
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getString(KEY, "").orEmpty()
                .lineSequence()
                .mapNotNull { it.substringBefore('|').takeIf { pkg -> pkg.isNotBlank() } }
                .toList()
        } catch (e: Exception) {
            emptyList()
        }
        cache = list
        return list
    }

    fun record(context: Context, packageName: String) {
        if (packageName.isBlank() || packageName == context.packageName) return
        val list = (listOf(packageName) + packages(context).filter { it != packageName }).take(MAX_STORED)
        cache = list
        write(context, list)
    }

    fun remove(context: Context, packageName: String) {
        val list = packages(context).filter { it != packageName }
        cache = list
        write(context, list)
    }

    fun clear(context: Context) {
        cache = emptyList()
        write(context, emptyList())
    }

    private fun write(context: Context, list: List<String>) {
        val now = System.currentTimeMillis()
        try {
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString(KEY, list.joinToString("\n") { "$it|$now" })
                .apply()
        } catch (e: Exception) {
            // Best effort: the in-memory list still serves this session.
        }
    }
}
