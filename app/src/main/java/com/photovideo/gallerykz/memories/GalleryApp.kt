package com.photovideo.gallerykz.memories

import com.photovideo.gallerykz.memories.ads.UnlockAds

import com.photovideo.gallerykz.memories.ads.GoogleAds

import com.photovideo.gallerykz.memories.ads.AppOpenAds

import com.photovideo.gallerykz.memories.ads.AdRemoteConfig

import android.app.Application
import android.content.res.Configuration
import com.photovideo.gallerykz.memories.events.EventWatch
import com.photovideo.gallerykz.memories.helper.LocaleHelper
import com.photovideo.gallerykz.memories.helper.ThemePrefs
import com.photovideo.gallerykz.memories.launcher.AppRecentsKey

/** Applies the saved theme (Settings > Theme) and the saved language before the first screen is drawn. */
class GalleryApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ThemePrefs.applySaved(this)
        LocaleHelper.init(this)
        // Recents key opens the launcher's Recent page while this app is the Home app.
        AppRecentsKey.install(this)
        // Charger plugged in / out, app installed / uninstalled: their screens (see events/).
        EventWatch.register(this)
        // Ads: the saved config first (no network), then the SDK, then a fresh fetch.
        AdRemoteConfig.restore(this)
        GoogleAds.init(this)
        AdRemoteConfig.fetch(this)
        AppOpenAds.install(this)
        UnlockAds.register(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A system change (rotation, dark mode...) must not drop the app's own language from this context.
        LocaleHelper.syncApplication()
    }
}
