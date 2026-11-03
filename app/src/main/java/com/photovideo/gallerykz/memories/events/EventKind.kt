package com.photovideo.gallerykz.memories.events

/** The four events that open a screen: the charger plugged in / out, an app installed / uninstalled. */
enum class EventKind(val key: String, val notificationId: Int) {
    CHARGE_IN("charge_in", 9102),
    CHARGE_OUT("charge_out", 9103),
    INSTALL("install", 9104),
    UNINSTALL("uninstall", 9101);

    companion object {
        fun fromKey(key: String?): EventKind? = entries.firstOrNull { it.key == key }
    }
}
