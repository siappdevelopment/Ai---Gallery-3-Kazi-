package com.photovideo.gallerykz.memories.model

/**
 * [nativeName] (primary line) and [englishName] (secondary line) are fixed labels shown as-is;
 * they are intentionally not string resources so they never change with the selected locale.
 */
data class LanguageItem(
    val tag: String,
    val flag: String,
    val nativeName: String,
    val englishName: String,
)
