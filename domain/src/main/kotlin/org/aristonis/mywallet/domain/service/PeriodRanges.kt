package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.TrackingPeriod
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Pure period → [DateRange] math (java.time). Week is ISO (Monday–Sunday). */
object PeriodRanges {
    fun of(period: TrackingPeriod, reference: LocalDate): DateRange = when (period) {
        TrackingPeriod.DAY -> DateRange(reference, reference)
        TrackingPeriod.WEEK -> DateRange(
            reference.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
            reference.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)),
        )
        TrackingPeriod.MONTH -> DateRange(
            reference.withDayOfMonth(1),
            reference.with(TemporalAdjusters.lastDayOfMonth()),
        )
        TrackingPeriod.YEAR -> DateRange(
            reference.withDayOfYear(1),
            reference.with(TemporalAdjusters.lastDayOfYear()),
        )
        TrackingPeriod.ALL_TIME -> DateRange.ALL_TIME
    }

    /**
     * Move [anchor] by [steps] whole periods (negative = earlier). Only the anchor moves; feed it back
     * to [of] for the neighbouring range. java.time clamps an impossible day (Jan 31 + 1 month is
     * Feb 28/29, Feb 29 + 1 year is Feb 28), which is safe because [of] widens to the whole unit anyway.
     *
     * @throws IllegalStateException for [TrackingPeriod.ALL_TIME], which already spans every date.
     */
    fun shift(period: TrackingPeriod, anchor: LocalDate, steps: Long): LocalDate = when (period) {
        TrackingPeriod.DAY -> anchor.plusDays(steps)
        TrackingPeriod.WEEK -> anchor.plusWeeks(steps)
        TrackingPeriod.MONTH -> anchor.plusMonths(steps)
        TrackingPeriod.YEAR -> anchor.plusYears(steps)
        TrackingPeriod.ALL_TIME -> error("all time has no neighbour to shift to")
    }
}
