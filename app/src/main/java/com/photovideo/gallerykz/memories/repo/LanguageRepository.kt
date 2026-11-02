package com.photovideo.gallerykz.memories.repo

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.helper.LanguagePrefs
import com.photovideo.gallerykz.memories.language.Languages
import com.photovideo.gallerykz.memories.model.LanguageItem
import com.photovideo.gallerykz.memories.model.LanguageScreenTexts
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

    /** Saves a language chosen in Settings (onboarding is not touched). */
    fun saveSettingSelection(tag: String) = LanguagePrefs.saveTagOnly(appContext, tag)

    /** The language the app shows now, as one of [Languages.all] tags (null when it is none of them). */
    fun currentTag(): String? {
        val chosen = AppCompatDelegate.getApplicationLocales()
        val tag = if (!chosen.isEmpty) chosen.toLanguageTags().substringBefore(',') else LanguagePrefs.getSavedTag(appContext) ?: Locale.getDefault().toLanguageTag()
        return Languages.all.firstOrNull { it.tag.equals(tag, ignoreCase = true) }?.tag
            ?: Languages.all.firstOrNull { it.tag.substringBefore('-').equals(tag.substringBefore('-'), ignoreCase = true) }?.tag
    }
}
