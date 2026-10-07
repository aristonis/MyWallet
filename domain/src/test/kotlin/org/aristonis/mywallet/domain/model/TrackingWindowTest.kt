package org.aristonis.mywallet.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TrackingWindowTest {

    private val aug = LocalDate.of(2026, 8, 20)

    @Test
    fun periodResolvesToTheRangeAroundItsAnchor() {
        val window = TrackingWindow.Period(TrackingPeriod.MONTH, aug)

        assertEquals(DateRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)), window.range)
        assertTrue(window.canStep)
    }

    @Test
    fun shiftingAPeriodMovesItsWholeRange() {
        val previous = TrackingWindow.Period(TrackingPeriod.MONTH, aug).shifted(-1)

        assertEquals(TrackingPeriod.MONTH, previous.period)
        assertEquals(DateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)), previous.range)
    }

    @Test
    fun allTimeCannotStep() {
        val window = TrackingWindow.Period(TrackingPeriod.ALL_TIME, aug)

        assertFalse(window.canStep)
        assertThrows(IllegalStateException::class.java) { window.shifted(1) }
    }

    @Test
    fun customWindowIsItsOwnRangeAndCannotStep() {
        val range = DateRange(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 17))
        val window = TrackingWindow.Custom(range)

        assertEquals(range, window.range)
        assertFalse(window.canStep)
    }

    @Test
    fun switchingPeriodKeepsTodayWhenTodayIsInTheWindow() {
        val today = LocalDate.of(2026, 3, 31)
        // Stepping back and forth clamps the anchor to the 28th; the switch must not land there.
        val march = TrackingWindow.Period(TrackingPeriod.MONTH, today).shifted(-1).shifted(1)

        assertEquals(TrackingWindow.Period(TrackingPeriod.DAY, today), march.withPeriod(TrackingPeriod.DAY, today))
    }

    @Test
    fun switchingPeriodFromAPastWindowStartsAtItsFirstDay() {
        val today = LocalDate.of(2026, 3, 31)
        val january = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 1, 15))

        assertEquals(
            TrackingWindow.Period(TrackingPeriod.WEEK, LocalDate.of(2026, 1, 1)),
            january.withPeriod(TrackingPeriod.WEEK, today),
        )
    }

    @Test
    fun switchingFromACustomRangeStartsAtItsFirstDay() {
        val custom = TrackingWindow.Custom(DateRange(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 17)))

        assertEquals(
            TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 8, 3)),
            custom.withPeriod(TrackingPeriod.MONTH, LocalDate.of(2026, 10, 7)),
        )
    }

    @Test
    fun steppingMovesAPeriod() {
        val september = TrackingWindow.Period(TrackingPeriod.MONTH, aug).stepped(1)

        assertEquals(DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)), september.range)
    }

    @Test
    fun steppingAWindowThatCannotStepLeavesItAlone() {
        val allTime = TrackingWindow.Period(TrackingPeriod.ALL_TIME, aug)
        val custom = TrackingWindow.Custom(DateRange(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 17)))

        assertEquals(allTime, allTime.stepped(1))
        assertEquals(custom, custom.stepped(-1))
    }
}
