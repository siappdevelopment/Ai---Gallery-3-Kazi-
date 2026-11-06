package com.photovideo.gallerykz.memories.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherAppSortTest {

    private fun order(vararg names: String): List<String> =
        names.sortedWith { a, b ->
            val c = LauncherAppSort.compareKeys(LauncherAppSort.keyOf(a, "pkg.$a"), LauncherAppSort.keyOf(b, "pkg.$b"))
            if (c != 0) c else a.compareTo(b)
        }

    @Test
    fun leadingSpacesAndSymbolsAreIgnored() {
        assertEquals("PDF Viewer", LauncherAppSort.keyOf("  PDF Viewer", "p"))
        assertEquals("XA Downloader", LauncherAppSort.keyOf("#XA Downloader", "p"))
        assertEquals("Zed)", LauncherAppSort.keyOf("  _#.(Zed)", "p"))
        assertEquals("Mail", LauncherAppSort.keyOf("​ Mail  ", "p"))
    }

    @Test
    fun emptyAndNullFallBackToPackage() {
        assertEquals("com.x", LauncherAppSort.keyOf(null, "com.X"))
        assertEquals("com.x", LauncherAppSort.keyOf("   ", "com.X"))
        assertEquals("###", LauncherAppSort.keyOf(" ### ", "com.x"))
    }

    @Test
    fun orderIsAlphabeticalCaseInsensitiveDigitsFirst() {
        val sorted = order("#XA Downloader", "AdMob App", " PDF Viewer", "alarm", "Albums", "2048", "_Zebra", "(Camera)", "all email", "10 Tiles")
        assertEquals(
            listOf("10 Tiles", "2048", "AdMob App", "alarm", "Albums", "all email", "(Camera)", " PDF Viewer", "#XA Downloader", "_Zebra"),
            sorted,
        )
    }

    @Test
    fun caseDoesNotChangeTheOrder() {
        assertEquals(listOf("apple", "Banana", "cherry"), order("cherry", "Banana", "apple"))
        assertEquals(listOf("APPLE", "banana", "Cherry"), order("Cherry", "banana", "APPLE"))
    }
}
