package org.aristonis.mywallet.domain.model

import org.aristonis.mywallet.domain.service.PeriodRanges
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DateRangeTest {

    @Test
    fun rejectsAReversedRange() {
        assertThrows(IllegalArgumentException::class.java) {
            DateRange(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 9))
        }
    }

    @Test
    fun acceptsASingleDay() {
        val day = LocalDate.of(2026, 8, 10)
        val range = DateRange(day, day)

        assertTrue(day in range)
        assertFalse(day.minusDays(1) in range)
        assertFalse(day.plusDays(1) in range)
    }

    @Test
    fun allTimeIsRecognisedAndAnOrdinaryRangeIsNot() {
        assertTrue(PeriodRanges.of(TrackingPeriod.ALL_TIME, LocalDate.of(2026, 8, 10)).isAllTime)
        assertTrue(DateRange.ALL_TIME.isAllTime)
        assertFalse(DateRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)).isAllTime)
    }
}
