package com.example.thirdgallery.repo

import android.content.Context
import androidx.lifecycle.LiveData
import com.example.thirdgallery.database.AppDatabase
import com.example.thirdgallery.database.HighlightCandidate
import com.example.thirdgallery.model.HighlightCard
import com.example.thirdgallery.model.HighlightKind
import com.example.thirdgallery.model.MediaFilter
import java.util.Calendar
import java.util.TimeZone

/**
 * Builds the For you highlight cards from the media index. Everything goes by the date a file was added to the
 * device and by the device's calendar and time zone.
 *
 * - Recently added: the last 24 hours.
 * - Last week: on Monday and Tuesday, the previous Monday-Sunday week.
 * - Last month: on the 1st to the 3rd, the previous calendar month.
 * - Last year: on January 1 to 3, the previous calendar year.
 * - Best of month / year / date: the single most relevant finished month (12 back), year (5 back) or day (60 back,
 *   more than 5 items added) - favorites count triple and older periods count less - showing as many of its best
 *   items as deserve it (about 15 of 20, a smaller share of bigger sets; see pickBest). A month or year that a
 *   "Last month" / "Last year" card already shows is skipped.
 *
 * Items already shown by an earlier card are skipped while enough others remain.
 * Blocking: call it off the main thread.
 */
class HighlightsRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).mediaDao()

    /** Changes whenever the media index changes, to know when to rebuild the cards. */
    fun observeMediaCount(): LiveData<Int> = dao.observeCount(MediaFilter.ALL.types, 0)

    fun load(now: Calendar = Calendar.getInstance()): List<HighlightCard> {
        val used = HashSet<Long>()
        val cards = ArrayList<HighlightCard>()
        val today = startOfDay(now)

        // Recently added
        val nowMs = now.timeInMillis
        window(HighlightKind.RECENT, nowMs - DAY_MS, nowMs + 1, COVERS, used, countAll = true)?.let(cards::add)

        // Last week: Monday and Tuesday only
        val daysSinceMonday = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7
        if (daysSinceMonday <= 1) {
            val thisMonday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -daysSinceMonday) }
            val lastMonday = (thisMonday.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -7) }
            window(HighlightKind.LAST_WEEK, lastMonday.timeInMillis, thisMonday.timeInMillis, COVERS, used, true)?.let(cards::add)
        }

        // Last month: the 1st to the 3rd
        var shownMonthStart: Long? = null
        if (now.get(Calendar.DAY_OF_MONTH) <= 3) {
            val thisMonth = startOfMonth(now, 0)
            val lastMonth = startOfMonth(now, -1)
            shownMonthStart = lastMonth.timeInMillis
            window(HighlightKind.LAST_MONTH, lastMonth.timeInMillis, thisMonth.timeInMillis, COVERS, used, true)?.let(cards::add)
        }

        // Last year: January 1 to 3
        var shownYearStart: Long? = null
        if (now.get(Calendar.MONTH) == Calendar.JANUARY && now.get(Calendar.DAY_OF_MONTH) <= 3) {
            val thisYear = startOfYear(now, 0)
            val lastYear = startOfYear(now, -1)
            shownYearStart = lastYear.timeInMillis
            window(HighlightKind.LAST_YEAR, lastYear.timeInMillis, thisYear.timeInMillis, COVERS, used, true)?.let(cards::add)
        }

        // Best of month: the most relevant finished month
        bestPeriod(HighlightKind.BEST_MONTH, (1..MONTHS_BACK).map { back ->
            Triple(startOfMonth(now, -back).timeInMillis, startOfMonth(now, -back + 1).timeInMillis, back)
        }.filter { it.first != shownMonthStart }, MONTH_DECAY, used)?.let(cards::add)

        // Best of year: the most relevant finished year
        bestPeriod(HighlightKind.BEST_YEAR, (1..YEARS_BACK).map { back ->
            Triple(startOfYear(now, -back).timeInMillis, startOfYear(now, -back + 1).timeInMillis, back)
        }.filter { it.first != shownYearStart }, YEAR_DECAY, used)?.let(cards::add)

        // Best of date: the most relevant day with more than 5 items added
        val todayKey = dayKey(today.timeInMillis)
        dao.busyDays(todayKey - DAYS_BACK, todayKey)
            .maxByOrNull { relevance(it.favorites, it.plain, (todayKey - it.day).toInt(), DATE_DECAY) }
            ?.let { day ->
                val start = localStartOfEpochDay(day.day)
                val picks = pickBest(dao.candidatesAddedOn(day.day), null, used)
                if (picks.isNotEmpty()) {
                    used.addAll(picks)
                    cards.add(HighlightCard(HighlightKind.BEST_DATE, start, start + DAY_MS, picks.size, picks))
                }
            }
        return cards
    }

    /** A card for a time window: [countAll] announces every item of the window, the collage shows the best ones. */
    private fun window(kind: HighlightKind, from: Long, to: Long, covers: Int, used: MutableSet<Long>, countAll: Boolean): HighlightCard? {
        val candidates = dao.candidatesAdded(from, to)
        if (candidates.isEmpty()) return null
        val picks = pickBest(candidates, covers, used)
        used.addAll(picks.take(covers))
        return HighlightCard(kind, from, to, if (countAll) candidates.size else picks.size, picks)
    }

    /**
     * Of the candidate periods (from, to, how many periods ago) with more than 5 items, the one with the highest
     * [relevance]: a busy period with many favorites wins over a quiet one, and a recent one over an old one.
     */
    private fun bestPeriod(
        kind: HighlightKind,
        periods: List<Triple<Long, Long, Int>>,
        decay: Double,
        used: MutableSet<Long>,
    ): HighlightCard? {
        val winner = periods
            .map { it to dao.periodStats(it.first, it.second) }
            .filter { it.second.total > MIN_ITEMS }
            .maxByOrNull { (period, stats) -> relevance(stats.favorites, stats.plain, period.third, decay) }
            ?: return null
        val (from, to, _) = winner.first
        val picks = pickBest(dao.candidatesAdded(from, to), null, used)
        if (picks.isEmpty()) return null
        used.addAll(picks)
        return HighlightCard(kind, from, to, picks.size, picks)
    }

    /** How worth recapping a period is: favorites count triple, older periods count less ([decay] per period back). */
    private fun relevance(favorites: Int, plain: Int, periodsAgo: Int, decay: Double): Double =
        (favorites * FAVORITE_WEIGHT + plain) * Math.pow(decay, periodsAgo.toDouble())

    /**
     * The best items of a period, best first. [limit] null means "as many as deserve it": see [bestCount].
     * Quality filters, in order: items an earlier card already shows are skipped (unless nothing else is left),
     * screenshots and clips shorter than 1.5 s are dropped (unless nothing else is left), and a burst (shots
     * less than 4 s apart) counts once, as its favorite or else its first shot. Then favorites come first, and
     * the rest is chosen by score (camera original, video of 3 s or more), spread evenly over time inside a score.
     */
    private fun pickBest(all: List<HighlightCandidate>, limit: Int?, used: Set<Long>): List<Long> {
        val fresh = all.filter { it.uid !in used }.ifEmpty { all }
        val decent = fresh.filter { !it.isShot && !(it.mediaType == VIDEO && it.durationMs < MIN_CLIP_MS) }.ifEmpty { fresh }
        val pool = dropBurstRepeats(decent)
        val n = limit ?: bestCount(pool.size)
        if (n <= 0) return emptyList()

        val picks = ArrayList<HighlightCandidate>(n)
        pool.filter { it.isFavorite }.sortedByDescending { it.dateMs }.take(n).forEach(picks::add)
        if (picks.size < n) {
            val byScore = pool.filter { !it.isFavorite }.groupBy { score(it) }.toSortedMap(compareByDescending { it })
            for (group in byScore.values) {
                val need = n - picks.size
                if (need <= 0) break
                picks.addAll(spread(group.sortedBy { it.dateMs }, need))
            }
        }
        return picks.map { it.uid }.distinct()
    }

    /** About three quarters of a small set, a shrinking share of a big one: 20 items give 15, 100 give about 34. */
    private fun bestCount(poolSize: Int): Int {
        if (poolSize <= SMALL_SET) return poolSize
        val share = BEST_SHARE * Math.sqrt(SHARE_BASE / poolSize).coerceAtMost(1.0)
        return Math.ceil(poolSize * share).toInt().coerceIn(SMALL_SET, MAX_BEST)
    }

    private fun score(item: HighlightCandidate): Int =
        (if (item.isCamera) 2 else 0) + (if (item.mediaType == VIDEO && item.durationMs >= GOOD_CLIP_MS) 1 else 0)

    /** Keeps one item of every run of shots taken less than [BURST_MS] apart: a favorite if there is one, else the first. */
    private fun dropBurstRepeats(items: List<HighlightCandidate>): List<HighlightCandidate> {
        if (items.size < 2) return items
        val sorted = items.sortedBy { it.dateMs }
        val kept = ArrayList<HighlightCandidate>(sorted.size)
        var run = ArrayList<HighlightCandidate>()
        fun flush() {
            if (run.isNotEmpty()) kept.add(run.firstOrNull { it.isFavorite } ?: run.first())
            run = ArrayList()
        }
        for (item in sorted) {
            if (run.isNotEmpty() && item.dateMs - run.last().dateMs >= BURST_MS) flush()
            run.add(item)
        }
        flush()
        return kept
    }

    /** [k] items taken at even intervals of a list sorted by time (all of it when it is not longer than [k]). */
    private fun spread(sorted: List<HighlightCandidate>, k: Int): List<HighlightCandidate> {
        if (k <= 0 || sorted.isEmpty()) return emptyList()
        if (sorted.size <= k) return sorted
        if (k == 1) return listOf(sorted[sorted.size / 2])
        return (0 until k).map { sorted[it * (sorted.size - 1) / (k - 1)] }
    }

    private fun startOfDay(now: Calendar): Calendar = (now.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    /** First instant of the month [offset] months from the one of [now]. */
    private fun startOfMonth(now: Calendar, offset: Int): Calendar = startOfDay(now).apply {
        set(Calendar.DAY_OF_MONTH, 1)
        add(Calendar.MONTH, offset)
    }

    private fun startOfYear(now: Calendar, offset: Int): Calendar = startOfDay(now).apply {
        set(Calendar.DAY_OF_YEAR, 1)
        add(Calendar.YEAR, offset)
    }

    private fun dayKey(ms: Long): Long = Math.floorDiv(ms + TimeZone.getDefault().getOffset(ms), DAY_MS)

    /** Local midnight of the calendar day that the epoch-day number [day] stands for. */
    private fun localStartOfEpochDay(day: Long): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = day * DAY_MS }
        return Calendar.getInstance().apply {
            clear()
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val COVERS = 3
        const val VIDEO = 3
        const val MIN_ITEMS = 5
        const val MONTHS_BACK = 12
        const val YEARS_BACK = 5
        const val DAYS_BACK = 60L

        // relevance: favorites weigh triple; each period back keeps this share of the weight
        const val FAVORITE_WEIGHT = 3
        const val DATE_DECAY = 0.95
        const val MONTH_DECAY = 0.9
        const val YEAR_DECAY = 0.7

        // quality rules
        const val MIN_CLIP_MS = 1_500L
        const val GOOD_CLIP_MS = 3_000L
        const val BURST_MS = 4_000L

        // how many deserve a place: share of the quality pool, shrinking for big pools
        const val SMALL_SET = 3
        const val BEST_SHARE = 0.75
        const val SHARE_BASE = 20.0

        /** Safety ceiling so a card never holds an unbounded list; far above what the rule gives for normal periods. */
        const val MAX_BEST = 120
    }
}
