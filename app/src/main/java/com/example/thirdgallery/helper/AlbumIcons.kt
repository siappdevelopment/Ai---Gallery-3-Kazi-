package com.example.thirdgallery.helper

import com.example.thirdgallery.R

object AlbumIcons {

    /** Icon for an album card or chip, picked from the folder name. */
    fun forName(name: String): Int {
        val n = name.lowercase()
        return when {
            n.contains("camera") || n == "dcim" -> R.drawable.ic_photo_camera
            n.contains("screenshot") || n.contains("screen") -> R.drawable.ic_smartphone
            else -> R.drawable.ic_folder
        }
    }
}
