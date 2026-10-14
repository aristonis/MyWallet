package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.NoTrendReason
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Which days a net worth series has a point for, or why it has none. */
object NetWorthBuckets {

    /** A window this long or shorter gets a point for every day. */
    const val DAILY_MAX_DAYS = 31L

    /** How far back a window may start and still draw a line. */
    private const val MAX_START_MONTHS = 12L

    sealed interface Plan {
        /** [dates] are ascending, at least two, none after today. */
        data class Days(val dates: List<LocalDate>) : Plan
        data class NoTrend(val reason: NoTrendReason) : Plan
    }

    /**
     * A window of at most [DAILY_MAX_DAYS] days gets a point per day; a longer one gets each calendar
     * month end inside it, then the last day. The last day is the window's end, or [today] when the
     * window ends later, since a point after today would read as a forecast. No line is drawn for an
     * all-time window or one that starts more than 12 calendar months before [today], and none when
     * fewer than two days are left.
     */
    fun plan(range: DateRange, today: LocalDate): Plan {
        if (range.isAllTime || range.start.isBefore(today.minusMonths(MAX_START_MONTHS))) {
            return Plan.NoTrend(NoTrendReason.START_TOO_OLD)
        }
        val last = minOf(range.endInclusive, today)
        if (last.isBefore(range.start)) return Plan.NoTrend(NoTrendReason.TOO_FEW_POINTS)

        val days = ChronoUnit.DAYS.between(range.start, range.endInclusive) + 1
        val dates = if (days <= DAILY_MAX_DAYS) daily(range.start, last) else monthEnds(range.start, last)
        return if (dates.size < 2) Plan.NoTrend(NoTrendReason.TOO_FEW_POINTS) else Plan.Days(dates)
    }

    private fun daily(first: LocalDate, last: LocalDate): List<LocalDate> =
        generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()

    private fun monthEnds(first: LocalDate, last: LocalDate): List<LocalDate> {
        val ends = generateSequence(YearMonth.from(first)) { it.plusMonths(1) }
            .map { it.atEndOfMonth() }
            .dropWhile { it.isBefore(first) }
            .takeWhile { it.isBefore(last) }
            .toList()
        return ends + last
    }
}
