package com.photovideo.gallerykz.memories

import com.photovideo.gallerykz.memories.ads.UnlockAds

import com.photovideo.gallerykz.memories.ads.GoogleAds

import com.photovideo.gallerykz.memories.ads.AppOpenAds

import com.photovideo.gallerykz.memories.ads.AdRemoteConfig

import android.app.Application
import com.photovideo.gallerykz.memories.helper.AdInsetsGuard
import com.photovideo.gallerykz.memories.helper.SystemNav
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
        AppRecentsKey.install(this)
         EventWatch.register(this)
         AdRemoteConfig.restore(this)
        GoogleAds.init(this)
        AdRemoteConfig.fetch(this)
        AppOpenAds.install(this)
        SystemNav.install(this)
        AdInsetsGuard.install(this)
        UnlockAds.register(this)
        com.photovideo.gallerykz.memories.launcher.LauncherApps.reload(this)
        // Memory controls: the hourly reminder alarm follows its switch (a process start restarts the hour).
        com.photovideo.gallerykz.memories.notification.MemoryNotificationScheduler.sync(this)
        // Installed apps are listed in the background from the start (during splash / onboarding), so the Home
        // folders are already filled when the launcher first opens instead of appearing a second later.

    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A system change (rotation, dark mode...) must not drop the app's own language from this context.
        LocaleHelper.syncApplication()
    }
}
