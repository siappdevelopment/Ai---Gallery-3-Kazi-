package com.photogallery.album.manager.utils

import android.content.Context
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.photogallery.album.manager.ads.AdRemoteConfig

/**
 * Firebase Remote Config switches of the built-in game ads. Until Firebase returns a value for a key, the
 * in-app default below is used (the ads are on), so they show even before the first fetch or when offline.
 * Set a key to false / 0 in the Firebase console to turn that placement off. Any Firebase problem falls back
 * to the defaults, so the launcher never depends on it.
 *
 * - [KEY_ICON_SHOW] / [KEY_ICON_COUNT]: game icons in the app drawer (count is capped at 40).
 * - [KEY_FOLDER_SHOW]: the two game folders on Home instead of the Google and Tools folders.
 */
object GameAdsConfig {

    const val KEY_ICON_SHOW = "LauncherApp_Quiz_Icon_Show"
    const val KEY_ICON_COUNT = "LauncherApp_Quiz_Icon_Count"
    const val KEY_FOLDER_SHOW = "LauncherApp_Game_Folder_Show"

    private const val DEFAULT_ICON_SHOW = true
    private const val DEFAULT_ICON_COUNT = 5L
    private const val DEFAULT_FOLDER_SHOW = true

    /** Fetches the latest values; new values apply the next time Home / the drawer render. */
    fun init(context: Context) {
        // VPN on: no Remote Config request until the VPN is off (AdRemoteConfig.resumeAfterVpn).
        if (VpnHelper.isVpnActive(context)) {
            deferredByVpn = true
            return
        }
        try {
            FirebaseRemoteConfig.getInstance().fetchAndActivate()
        } catch (e: Exception) {
            // Firebase unavailable: the defaults stay.
        }
    }

    /** True while [init] was held back by a VPN. */
    @Volatile
    var deferredByVpn = false
        private set

    fun resumeAfterVpn(context: Context) {
        if (!deferredByVpn) return
        deferredByVpn = false
        init(context)
    }

    // The ad config JSON (LauncherAppScreen) wins when it has the key; otherwise the flat Remote Config key / default below.
    val quizIconShow: Boolean
        get() = AdRemoteConfig.config.launcher.quizIconShow ?: readBoolean(KEY_ICON_SHOW, DEFAULT_ICON_SHOW)

    /** How many game icons the drawer shows: 0 when off, otherwise 0..[LauncherQuizGames.MAX_GAMES]. */
    val quizIconCount: Int
        get() = if (!quizIconShow) 0 else (AdRemoteConfig.config.launcher.quizIconCount?.toLong() ?: readLong(KEY_ICON_COUNT, DEFAULT_ICON_COUNT))
            .coerceIn(0L, LauncherQuizGames.MAX_GAMES.toLong()).toInt()

    val gameFoldersShow: Boolean
        get() = AdRemoteConfig.config.launcher.gameFolderShow ?: readBoolean(KEY_FOLDER_SHOW, DEFAULT_FOLDER_SHOW)

    /** The value Firebase holds for [key], or null while it has none (not fetched yet, or not set in the console). */
    private fun remoteValue(key: String) = try {
        FirebaseRemoteConfig.getInstance().getValue(key).takeIf { it.source == FirebaseRemoteConfig.VALUE_SOURCE_REMOTE }
    } catch (e: Exception) {
        null
    }

    private fun readBoolean(key: String, default: Boolean): Boolean = try {
        remoteValue(key)?.asBoolean() ?: default
    } catch (e: Exception) {
        default
    }

    private fun readLong(key: String, default: Long): Long = try {
        remoteValue(key)?.asLong() ?: default
    } catch (e: Exception) {
        default
    }
}
