package com.example.thirdgallery.helper

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object LocaleHelper {

    /** Applies the language app-wide. AppCompat persists it and recreates visible activities. */
    fun apply(tag: String) {
        try {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        } catch (e: Exception) {
            // Falling back to the current locale is safer than crashing.
        }
    }
}
