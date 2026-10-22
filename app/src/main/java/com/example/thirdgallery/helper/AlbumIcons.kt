package com.example.thirdgallery.helper

import com.example.thirdgallery.R

object AlbumIcons {

    /**
     * Outlined icon for an album card or chip, picked from the folder name. Matches the design's
     * library set: camera, screenshots, portrait, downloads; everything else gets a folder.
     */
    fun forName(name: String?): Int {
        val n = name.orEmpty().lowercase()
        return when {
            n.contains("camera") || n == "dcim" -> R.drawable.ic_album_camera
            n.contains("screenshot") || n.contains("screen") -> R.drawable.ic_album_screenshot
            n.contains("portrait") -> R.drawable.ic_album_portrait
            n.contains("download") -> R.drawable.ic_album_download
            else -> R.drawable.ic_album_folder
        }
    }
}
