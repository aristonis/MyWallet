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
        TrackingPeriod.ALL_TIME -> DateRange(LocalDate.MIN, LocalDate.MAX)
    }
}
