package com.example.thirdgallery.repo

import android.content.Context
import android.content.res.Configuration
import com.example.thirdgallery.R
import com.example.thirdgallery.helper.LanguagePrefs
import com.example.thirdgallery.language.Languages
import com.example.thirdgallery.model.LanguageItem
import com.example.thirdgallery.model.LanguageScreenTexts
import java.util.Locale

class LanguageRepository(context: Context) {

    private val appContext: Context = context.applicationContext

    fun isValidTag(tag: String?): Boolean = tag != null && Languages.all.any { it.tag == tag }

    /**
     * Context whose resources are loaded for [tag], so strings can be previewed in that language
     * without recreating the screen. Missing translations fall back to the default resources;
     * a null tag or any failure returns the app's current context.
     */
    fun localizedContext(tag: String?): Context {
        if (!isValidTag(tag)) return appContext
        return try {
            val config = Configuration(appContext.resources.configuration)
            config.setLocale(Locale.forLanguageTag(tag!!))
            appContext.createConfigurationContext(config)
        } catch (e: Exception) {
            appContext
        }
    }

    fun getScreenTexts(localized: Context): LanguageScreenTexts = LanguageScreenTexts(
        title = localized.getString(R.string.language_title),
        subtitle = localized.getString(R.string.language_subtitle),
        searchHint = localized.getString(R.string.language_search_hint),
        continueLabel = localized.getString(R.string.language_continue),
        noResults = localized.getString(R.string.language_no_results),
    )

    /** Languages whose native or English name contains [query]. */
    fun search(query: String): List<LanguageItem> {
        val q = query.trim()
        if (q.isEmpty()) return Languages.all
        return Languages.all.filter {
            it.nativeName.contains(q, ignoreCase = true) ||
                it.englishName.contains(q, ignoreCase = true)
        }
    }

    fun saveSelection(tag: String) = LanguagePrefs.save(appContext, tag)
}
