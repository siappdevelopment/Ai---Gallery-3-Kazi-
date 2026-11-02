package com.example.thirdgallery

import android.app.Application
import android.content.res.Configuration
import com.example.thirdgallery.helper.LocaleHelper
import com.example.thirdgallery.helper.ThemePrefs

/** Applies the saved theme (Settings > Theme) and the saved language before the first screen is drawn. */
class GalleryApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ThemePrefs.applySaved(this)
        LocaleHelper.init(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A system change (rotation, dark mode...) must not drop the app's own language from this context.
        LocaleHelper.syncApplication()
    }
}
