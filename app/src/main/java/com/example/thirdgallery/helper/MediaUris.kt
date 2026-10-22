package com.example.thirdgallery.helper

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import com.example.thirdgallery.database.MediaTypes

object MediaUris {

    /** Content URI of a photo or video. */
    fun of(mediaId: Long, mediaType: Int): Uri {
        val base = if (mediaType == MediaTypes.VIDEO) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return ContentUris.withAppendedId(base, mediaId)
    }

    /** Same, from the database's combined id (see [MediaTypes.uid]). */
    fun ofUid(uid: Long): Uri = of(MediaTypes.mediaIdOf(uid), MediaTypes.typeOf(uid))
}
