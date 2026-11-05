package com.photovideo.gallerykz.memories

import com.photovideo.gallerykz.memories.launcher.DockCandidate
import com.photovideo.gallerykz.memories.launcher.DockDedupe
import org.junit.Assert.assertEquals
import org.junit.Test

class DockDedupeTest {

    private fun app(pkg: String, label: String, picture: Int? = pkg.hashCode()) = DockCandidate(pkg, label, picture)

    @Test
    fun normalPhone_showsAllFourApps() {
        val dock = listOf(
            app("com.google.android.contacts", "Contacts"),
            app("com.google.android.dialer", "Phone"),
            app("com.google.android.apps.messaging", "Messages"),
            app("com.android.chrome", "Chrome"),
        )
        assertEquals(listOf(true, true, true, true), DockDedupe.shown(dock))
    }

    @Test
    fun oneAppForContactsAndPhone_isShownOnce() {
        // Xiaomi style: the Contacts app is also the default dialer.
        val dock = listOf(
            app("com.android.contacts", "Contacts"),
            app("com.android.contacts", "Contacts"),
            app("com.google.android.apps.messaging", "Messages"),
            app("com.android.chrome", "Chrome"),
        )
        assertEquals(listOf(true, false, true, true), DockDedupe.shown(dock))
    }

    @Test
    fun twoPackagesWithTheSameName_areShownOnce() {
        val dock = listOf(
            app("com.android.contacts", "Contacts", picture = 1),
            app("com.google.android.contacts", "Contacts", picture = 2),
            app("com.google.android.apps.messaging", "Messages"),
            app("com.android.chrome", "Chrome"),
        )
        assertEquals(listOf(true, false, true, true), DockDedupe.shown(dock))
    }

    @Test
    fun twoPackagesWithTheSamePicture_areShownOnce() {
        val dock = listOf(
            app("com.android.contacts", "Contacts", picture = 7),
            app("com.miui.contacts", "Phone", picture = 7),
            app("com.google.android.apps.messaging", "Messages"),
            app("com.android.chrome", "Chrome"),
        )
        assertEquals(listOf(true, false, true, true), DockDedupe.shown(dock))
    }

    @Test
    fun missingApps_areNotShownAndDoNotHideOthers() {
        val dock = listOf(null, app("com.google.android.dialer", "Phone"), null, app("com.android.chrome", "Chrome"))
        assertEquals(listOf(false, true, false, true), DockDedupe.shown(dock))
    }

    @Test
    fun nameIsComparedIgnoringCaseAndSpaces() {
        val dock = listOf(app("a.one", " Contacts "), app("b.two", "contacts"))
        assertEquals(listOf(true, false), DockDedupe.shown(dock))
    }

    @Test
    fun appsWithoutAPictureAreComparedByPackageAndName_only() {
        val dock = listOf(app("a.one", "One", picture = null), app("b.two", "Two", picture = null))
        assertEquals(listOf(true, true), DockDedupe.shown(dock))
    }
}
