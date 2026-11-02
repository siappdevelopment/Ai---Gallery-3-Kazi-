package com.photovideo.gallerykz.memories.callend

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.photovideo.gallerykz.memories.R

/** How a call ended: its key travels in the intent, its label is shown under the number. */
enum class CallKind(val key: String, @StringRes val label: Int, @DrawableRes val icon: Int) {
    INCOMING("incoming", R.string.callend_incoming, R.drawable.ic_call_received_cl_end),
    OUTGOING("outgoing", R.string.callend_outgoing, R.drawable.ic_call_made_cl_end),
    MISSED("missed", R.string.callend_missed, R.drawable.ic_call_missed_cl_end);

    companion object {
        fun fromKey(key: String?): CallKind = entries.firstOrNull { it.key == key } ?: INCOMING
    }
}
