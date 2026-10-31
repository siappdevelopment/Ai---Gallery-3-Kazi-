package com.example.thirdgallery.model

/** The kinds of cards in the For you "Highlights" row, in the order they are shown. */
enum class HighlightKind {
    RECENT,
    LAST_WEEK,
    LAST_MONTH,
    LAST_YEAR,
    BEST_MONTH,
    BEST_YEAR,
    BEST_DATE,
}

/**
 * One highlight card. [uids] are the media shown, best first (the first three are the collage); [count] is the
 * number the card announces. [fromMs]..[toMs) is the period the card is about (device-local calendar boundaries).
 */
data class HighlightCard(
    val kind: HighlightKind,
    val fromMs: Long,
    val toMs: Long,
    val count: Int,
    val uids: List<Long>,
) {
    /** Stable across refreshes while the card stays the same, so the row does not flicker. */
    val id: Long get() = kind.ordinal * 1_000_000_007L + fromMs
}
