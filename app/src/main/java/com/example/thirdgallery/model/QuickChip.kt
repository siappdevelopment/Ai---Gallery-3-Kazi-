package com.example.thirdgallery.model

import androidx.annotation.DrawableRes

/** A collection shortcut at the top of the Timeline (album, videos or favorites). */
data class QuickChip(
    @DrawableRes val icon: Int,
    val label: String,
    val count: Int,
    /** What the chip opens: a device folder ([id] = bucket id) or Videos / Favorites. */
    val kind: AlbumKind = AlbumKind.DEVICE,
    val id: Long = 0L,
)
