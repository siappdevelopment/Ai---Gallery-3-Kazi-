package com.photovideo.gallerykz.memories.launcher

import java.text.Collator

/** How the launcher orders app names. Pure Kotlin, so it can be tested without a phone. Shown names are never changed by it. */
object LauncherAppSort {

    /**
     * The text a name is ordered by. Leading spaces (also no-break and zero-width ones) and leading symbols such as "#", "_", "."
     * or "(" are dropped, so " PDF Viewer" and "#XA Downloader" sit with the P and the X. A name made only of such characters
     * keeps its trimmed text, and a blank or null one falls back to the package name, so the key is never empty.
     */
    fun keyOf(label: String?, packageName: String): String {
        val text = label.orEmpty()
            .filterNot { it == '​' || it == '‌' || it == '‍' || it == '﻿' }
            .trim { it.isWhitespace() || it == ' ' }
        if (text.isEmpty()) return packageName.lowercase()
        val start = text.indexOfFirst { Character.isLetterOrDigit(it) }
        return if (start <= 0) text else text.substring(start)
    }

    /** Case-insensitive, accent-tolerant order of two keys (digits first, then letters). */
    fun compareKeys(a: String, b: String): Int = collator().compare(a, b)

    /** Order by key; equal names fall back to the lower-case key and the package so the order never flips between reloads. */
    fun <T> comparator(key: (T) -> String, packageOf: (T) -> String): Comparator<T> {
        val collator = collator()
        return Comparator<T> { a, b -> collator.compare(key(a), key(b)) }
            .thenBy { key(it).lowercase() }
            .thenBy { packageOf(it) }
    }

    private fun collator(): Collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
}
