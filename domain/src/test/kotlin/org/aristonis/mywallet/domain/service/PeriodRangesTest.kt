package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
