package com.example.thirdgallery.helper

import android.app.Application
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleHelper {

    private var app: Application? = null

    /**
     * Called once when the app starts. Brings the saved language back (AppCompat keeps it itself; the language saved by the
     * language screen is the safety net) and makes the app context speak it too.
     */
    fun init(application: Application) {
        app = application
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            LanguagePrefs.getSavedTag(application)?.let { apply(it) }
        }
        syncApplication()
    }

    /**
     * Applies the language app-wide. AppCompat persists it and recreates every open screen; the application context is
     * updated too, because toasts, notifications and view models read their texts through it.
     */
    fun apply(tag: String) {
        try {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        } catch (e: Exception) {
            // Falling back to the current locale is safer than crashing.
        }
        syncApplication()
    }

    /**
     * Before Android 13 AppCompat only changes the language of the activities, so everything that reads strings through
     * the application context would keep the phone's language. This puts the app's language on that context as well.
     */
    fun syncApplication() {
        val application = app ?: return
        val locale: Locale = AppCompatDelegate.getApplicationLocales().takeIf { !it.isEmpty }?.get(0) ?: return
        try {
            Locale.setDefault(locale)
            val resources = application.resources
            val config = Configuration(resources.configuration)
            config.setLocale(locale)
            @Suppress("DEPRECATION")
            resources.updateConfiguration(config, resources.displayMetrics)
        } catch (e: Exception) {
            // The texts then follow the phone's language for non-screen strings only.
        }
    }
}
