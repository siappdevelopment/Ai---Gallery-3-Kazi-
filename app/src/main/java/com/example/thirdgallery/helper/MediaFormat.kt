package com.example.thirdgallery.helper

import android.content.Context
import android.text.format.DateFormat
import com.example.thirdgallery.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Text formatting for the gallery grid (video lengths and day headers). */
object MediaFormat {

    private const val DAY_MS = 86_400_000L

    /** 83000 -> "1:23", 3_723_000 -> "1:02:03". */
    fun duration(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0) / 1000).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** "Today", "Yesterday", "Monday, October 19" (this year) or "October 19, 2025" (older). */
    fun dayTitle(context: Context, dayKey: Long): String {
        val now = System.currentTimeMillis()
        val today = Math.floorDiv(now + TimeZone.getDefault().getOffset(now), DAY_MS)
        return when (today - dayKey) {
            0L -> context.getString(R.string.photo_today)
            1L -> context.getString(R.string.photo_yesterday)
            else -> {
                val locale = context.resources.configuration.locales.get(0) ?: Locale.getDefault()
                val date = Date(dayKey * DAY_MS)
                // Day keys are civil dates, so format them in UTC to avoid shifting the day.
                val sameYear = yearOf(date, locale) == yearOf(Date(today * DAY_MS), locale)
                val skeleton = if (sameYear) "EEEEMMMMd" else "yMMMMd"
                SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .format(date)
            }
        }
    }

    private fun yearOf(date: Date, locale: Locale): String =
        SimpleDateFormat("y", locale).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)
}
