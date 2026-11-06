package com.photogallery.album.manager.model

import android.graphics.drawable.Drawable

/** One installed app shown in a Home-page folder or in the app drawer. */
data class LauncherApp(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    /** First install time in ms; used by the "installed date" sort order. */
    val installTime: Long = 0L,
    /** Set only for a built-in game ad: the link a tap opens. Such a row is not an installed app. */
    val gameUrl: String? = null
)
