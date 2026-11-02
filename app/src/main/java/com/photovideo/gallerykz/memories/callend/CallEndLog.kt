package com.photovideo.gallerykz.memories.callend

import android.util.Log

/** Logcat tag of the call summary flow: filter on "Kazicallend" to follow a call from the phone state to the screen. */
object CallEndLog {
    const val TAG = "Kazicallend"

    fun d(message: String) {
        Log.d(TAG, message)
    }
}
