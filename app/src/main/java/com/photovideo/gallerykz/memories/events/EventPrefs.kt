package com.photovideo.gallerykz.memories.events

import android.content.Context

/** Which event screens are switched on (all four by default). */
object EventPrefs {

    private const val FILE = "event_screens"

    /** The config's flag of the kind (all on until a config is loaded) and the local switch. */
    fun isEnabled(context: Context, kind: EventKind): Boolean {
        val c = com.photovideo.gallerykz.memories.ads.AdRemoteConfig.config
        val remote = when (kind) {
            EventKind.CHARGE_IN -> c.chargeInShow
            EventKind.CHARGE_OUT -> c.chargeOutShow
            EventKind.INSTALL -> c.installShow
            EventKind.UNINSTALL -> c.uninstallShow
        }
        if (!remote) return false
        return try {
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("show_" + kind.key, true)
        } catch (e: Exception) {
            true
        }
    }
}
