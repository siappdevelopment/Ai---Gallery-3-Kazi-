package com.example.thirdgallery.helper

import androidx.annotation.DrawableRes
import com.example.thirdgallery.R

/** Maps a gallery photo index (as stored in the database) to its bundled drawable. */
object HomePhotos {

    @DrawableRes
    fun res(index: Int): Int = when (index) {
        3 -> R.drawable.home_p3
        4 -> R.drawable.home_p4
        5 -> R.drawable.home_p5
        7 -> R.drawable.home_p7
        11 -> R.drawable.home_p11
        12 -> R.drawable.home_p12
        13 -> R.drawable.home_p13
        14 -> R.drawable.home_p14
        15 -> R.drawable.home_p15
        16 -> R.drawable.home_p16
        17 -> R.drawable.home_p17
        18 -> R.drawable.home_p18
        19 -> R.drawable.home_p19
        20 -> R.drawable.home_p20
        21 -> R.drawable.home_p21
        27 -> R.drawable.home_p27
        28 -> R.drawable.home_p28
        29 -> R.drawable.home_p29
        30 -> R.drawable.home_p30
        31 -> R.drawable.home_p31
        33 -> R.drawable.home_p33
        34 -> R.drawable.home_p34
        35 -> R.drawable.home_p35
        39 -> R.drawable.home_p39
        40 -> R.drawable.home_p40
        48 -> R.drawable.home_p48
        50 -> R.drawable.home_p50
        51 -> R.drawable.home_p51
        62 -> R.drawable.home_p62
        else -> R.drawable.home_p3
    }
}
