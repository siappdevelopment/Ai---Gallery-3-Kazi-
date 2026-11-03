package com.photovideo.gallerykz.memories.events

import android.content.Context

/** Which event screens are switched on (all four by default). */
object EventPrefs {

    private const val FILE = "event_screens"

    fun isEnabled(context: Context, kind: EventKind): Boolean = try {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("show_" + kind.key, true)
    } catch (e: Exception) {
        true
    }
}
