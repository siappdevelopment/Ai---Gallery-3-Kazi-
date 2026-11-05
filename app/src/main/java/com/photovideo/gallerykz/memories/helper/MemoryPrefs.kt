package com.photovideo.gallerykz.memories.helper

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * The six switches of Memory controls, saved on the device. Nothing here is controlled remotely.
 *
 * - [isDailyMemories]: shows the Highlights row of For you and the Memories card of Home. It does not gate the reminder.
 * - [isSensitiveMemories]: stored only. The app has no sensitive-content classification yet, so nothing is held back
 *   today; a future classifier must read this before it lets a photo into a memory.
 * - [isFaceGrouping]: looking for faces, and showing People. Turning it off keeps the faces found so far.
 * - [isLocationMemories]: reading the place saved inside photos, and showing Places. Turning it off keeps what was read.
 * - [isDateMemories]: the "Best of <day>" highlight, a memory of one single day.
 * - [isMemoryNotifications]: the reminder notification (see MemoryNotificationScheduler).
 *
 * A change is saved at once and also announced on [changes], so lists that depend on a switch can rebuild.
 */
object MemoryPrefs {

    private const val FILE = "memory_prefs"
    private const val KEY_DAILY = "daily_memories"
    private const val KEY_SENSITIVE = "sensitive_memories"
    private const val KEY_FACES = "memory_face_grouping"
    private const val KEY_LOCATION = "memory_location"
    private const val KEY_DATES = "memory_dates"
    private const val KEY_NOTIFICATIONS = "memory_notifications"

    private val _changes = MutableLiveData<Int>()

    /** Emits after every change of a switch (nothing on subscribing), for lists that must follow the switches. */
    val changes: LiveData<Int> = _changes
    private var version = 0

    fun isDailyMemories(context: Context) = get(context, KEY_DAILY, true)
    fun isSensitiveMemories(context: Context) = get(context, KEY_SENSITIVE, false)
    fun isFaceGrouping(context: Context) = get(context, KEY_FACES, true)
    fun isLocationMemories(context: Context) = get(context, KEY_LOCATION, true)
    fun isDateMemories(context: Context) = get(context, KEY_DATES, true)
    fun isMemoryNotifications(context: Context) = get(context, KEY_NOTIFICATIONS, false)

    fun setDailyMemories(context: Context, on: Boolean) = set(context, KEY_DAILY, on)
    fun setSensitiveMemories(context: Context, on: Boolean) = set(context, KEY_SENSITIVE, on)
    fun setFaceGrouping(context: Context, on: Boolean) = set(context, KEY_FACES, on)
    fun setLocationMemories(context: Context, on: Boolean) = set(context, KEY_LOCATION, on)
    fun setDateMemories(context: Context, on: Boolean) = set(context, KEY_DATES, on)
    fun setMemoryNotifications(context: Context, on: Boolean) = set(context, KEY_NOTIFICATIONS, on)

    private fun get(context: Context, key: String, default: Boolean) = prefs(context).getBoolean(key, default)

    private fun set(context: Context, key: String, on: Boolean) {
        prefs(context).edit { putBoolean(key, on) }
        synchronized(this) { version++ }
        _changes.postValue(version)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
