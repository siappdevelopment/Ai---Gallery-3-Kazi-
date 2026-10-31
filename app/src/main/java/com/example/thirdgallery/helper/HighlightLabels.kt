package com.example.thirdgallery.helper

import android.content.Context
import android.text.format.DateUtils
import com.example.thirdgallery.R
import com.example.thirdgallery.model.HighlightCard
import com.example.thirdgallery.model.HighlightKind
import java.util.Calendar

/** The wording of a highlight (card and detail screen): its title, the period it covers, and "period · N photos". */
object HighlightLabels {

    fun title(context: Context, card: HighlightCard): String = when (card.kind) {
        HighlightKind.RECENT -> context.getString(R.string.highlight_recent)
        HighlightKind.LAST_WEEK -> context.getString(R.string.highlight_last_week)
        HighlightKind.LAST_MONTH -> context.getString(R.string.highlight_last_month)
        HighlightKind.LAST_YEAR -> context.getString(R.string.highlight_last_year)
        HighlightKind.BEST_MONTH -> context.getString(
            R.string.highlight_best_of,
            DateUtils.formatDateTime(context, card.fromMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_MONTH_DAY or DateUtils.FORMAT_NO_YEAR),
        )
        HighlightKind.BEST_YEAR -> context.getString(R.string.highlight_best_of, year(card.fromMs).toString())
        HighlightKind.BEST_DATE -> context.getString(
            R.string.highlight_best_of,
            DateUtils.formatDateTime(context, card.fromMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_MONTH),
        )
    }

    /** What the highlight covers, under the title; empty when the title already says it. */
    fun period(context: Context, card: HighlightCard): String = when (card.kind) {
        HighlightKind.RECENT -> context.getString(R.string.highlight_last_24h)
        HighlightKind.LAST_WEEK -> DateUtils.formatDateRange(
            context,
            card.fromMs,
            card.toMs - 1,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
        )
        HighlightKind.LAST_MONTH -> DateUtils.formatDateTime(
            context,
            card.fromMs,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_MONTH_DAY or DateUtils.FORMAT_SHOW_YEAR,
        )
        HighlightKind.LAST_YEAR -> year(card.fromMs).toString()
        HighlightKind.BEST_MONTH, HighlightKind.BEST_DATE -> year(card.fromMs).toString()
        HighlightKind.BEST_YEAR -> ""
    }

    /** "period · 38 photos", or just "38 photos" when there is no period text. */
    fun meta(context: Context, card: HighlightCard, count: Int): String {
        val photos = context.resources.getQuantityString(R.plurals.intro2_photos, count, count)
        val period = period(context, card)
        return if (period.isEmpty()) photos else context.getString(R.string.viewer_meta, period, photos)
    }

    private fun year(ms: Long): Int = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.YEAR)
}
