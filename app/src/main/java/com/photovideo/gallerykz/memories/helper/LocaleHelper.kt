package com.photovideo.gallerykz.memories.helper

import android.app.Application
import android.content.Context
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

    /** The language the app must speak: the one AppCompat keeps, else the one the language screen saved; null = the phone's. */
    fun wantedLocale(context: Context): Locale? {
        val chosen = try {
            AppCompatDelegate.getApplicationLocales()
        } catch (e: Exception) {
            null
        }
        if (chosen != null && !chosen.isEmpty) return chosen.get(0)
        val tag = LanguagePrefs.getSavedTag(context) ?: return null
        return Locale.forLanguageTag(tag)
    }

    /** [base] with the app's language on its resources (unchanged when the user never chose one). */
    fun wrap(base: Context): Context {
        val locale = wantedLocale(base) ?: return base
        return try {
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            config.setLocales(android.os.LocaleList(locale))
            base.createConfigurationContext(config)
        } catch (e: Exception) {
            base
        }
    }

    /** True when [context] shows a different language than the app's. */
    fun isStale(context: Context): Boolean {
        val wanted = wantedLocale(context) ?: return false
        val shown = context.resources.configuration.locales.get(0) ?: return false
        return !shown.language.equals(wanted.language, ignoreCase = true)
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
