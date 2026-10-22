package com.example.thirdgallery.language

import com.example.thirdgallery.model.LanguageItem

/** Single source of truth for the languages the app ships. Keep in sync with xml/locales_config.xml. */
object Languages {

    val all: List<LanguageItem> = listOf(
        LanguageItem("en-US", "🇺🇸", "English", "English (US)"),
        LanguageItem("en-GB", "🇬🇧", "English (UK)", "English (UK)"),
        LanguageItem("hi", "🇮🇳", "हिन्दी", "Hindi"),
        LanguageItem("ru", "🇷🇺", "Русский", "Russian"),
        LanguageItem("it", "🇮🇹", "Italiano", "Italian"),
        LanguageItem("fr", "🇫🇷", "Français", "French"),
        LanguageItem("es", "🇪🇸", "Español", "Spanish"),
        LanguageItem("ja", "🇯🇵", "日本語", "Japanese"),
        LanguageItem("ko", "🇰🇷", "한국어", "Korean"),
        LanguageItem("af", "🇿🇦", "Afrikaans", "Afrikaans"),
        LanguageItem("de", "🇩🇪", "Deutsch", "German"),
        LanguageItem("el", "🇬🇷", "Ελληνικά", "Greek"),
        LanguageItem("fil", "🇵🇭", "Filipino", "Filipino"),
        LanguageItem("id", "🇮🇩", "Bahasa Indonesia", "Indonesian"),
        LanguageItem("nl", "🇳🇱", "Nederlands", "Dutch"),
        LanguageItem("pt-BR", "🇧🇷", "Português (Brasil)", "Portuguese (Brazil)"),
        LanguageItem("pt-PT", "🇵🇹", "Português (Portugal)", "Portuguese (Portugal)"),
        LanguageItem("th", "🇹🇭", "ไทย", "Thai"),
        LanguageItem("tr", "🇹🇷", "Türkçe", "Turkish"),
        LanguageItem("zh", "🇨🇳", "中文", "Chinese"),
    )
}
