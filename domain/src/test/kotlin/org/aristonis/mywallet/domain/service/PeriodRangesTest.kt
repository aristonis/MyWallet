package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PeriodRangesTest {

    // 2026-07-15 is a Wednesday.
    private val ref = LocalDate.of(2026, 7, 15)

    @Test
    fun day_isJustThatDay() {
        assertEquals(DateRange(ref, ref), PeriodRanges.of(TrackingPeriod.DAY, ref))
    }

    @Test
    fun week_isMondayToSunday() {
        assertEquals(
            DateRange(LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19)),
            PeriodRanges.of(TrackingPeriod.WEEK, ref),
        )
    }

    @Test
    fun month_isFirstToLastDay() {
        assertEquals(
            DateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)),
            PeriodRanges.of(TrackingPeriod.MONTH, ref),
        )
    }

    @Test
    fun year_isJanToDec() {
        assertEquals(
            DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
            PeriodRanges.of(TrackingPeriod.YEAR, ref),
        )
    }

    @Test
    fun allTime_containsAnyDate() {
        val range = PeriodRanges.of(TrackingPeriod.ALL_TIME, ref)
        assertTrue(LocalDate.of(1900, 1, 1) in range)
        assertTrue(LocalDate.of(3000, 12, 31) in range)
    }

    @Test
    fun contains_respectsBoundaries() {
        val month = PeriodRanges.of(TrackingPeriod.MONTH, ref)
        assertTrue(LocalDate.of(2026, 7, 1) in month)
        assertTrue(LocalDate.of(2026, 7, 31) in month)
        assertFalse(LocalDate.of(2026, 6, 30) in month)
        assertFalse(LocalDate.of(2026, 8, 1) in month)
    }

    @Test
    fun previousMonthCrossesTheYear() {
        val anchor = PeriodRanges.shift(TrackingPeriod.MONTH, LocalDate.of(2026, 1, 15), -1)
        assertEquals(
            DateRange(LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 31)),
            PeriodRanges.of(TrackingPeriod.MONTH, anchor),
        )
    }

    @Test
    fun nextMonthFromThe31stClampsIntoFebruary() {
        val anchor = PeriodRanges.shift(TrackingPeriod.MONTH, LocalDate.of(2026, 1, 31), 1)
        assertEquals(LocalDate.of(2026, 2, 28), anchor)
        assertEquals(
            DateRange(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)),
            PeriodRanges.of(TrackingPeriod.MONTH, anchor),
        )
    }

    @Test
    fun nextYearFromLeapDayCoversTheWholeYear() {
        val anchor = PeriodRanges.shift(TrackingPeriod.YEAR, LocalDate.of(2024, 2, 29), 1)
        assertEquals(
            DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)),
            PeriodRanges.of(TrackingPeriod.YEAR, anchor),
        )
    }

    @Test
    fun weekSpansTheYearBoundary() {
        // 2026-01-01 is a Thursday, so its ISO week starts on Monday 2025-12-29.
        val anchor = PeriodRanges.shift(TrackingPeriod.WEEK, LocalDate.of(2025, 12, 25), 1)
        assertEquals(
            DateRange(LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 4)),
            PeriodRanges.of(TrackingPeriod.WEEK, anchor),
        )
    }

    @Test
    fun dayStepsOneDay() {
        assertEquals(LocalDate.of(2026, 3, 1), PeriodRanges.shift(TrackingPeriod.DAY, LocalDate.of(2026, 2, 28), 1))
    }

    @Test
    fun allTimeCannotShift() {
        assertThrows(IllegalStateException::class.java) {
            PeriodRanges.shift(TrackingPeriod.ALL_TIME, ref, 1)
        }
    }
}
