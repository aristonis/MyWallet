package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.NoTrendReason
import org.aristonis.mywallet.domain.service.NetWorthBuckets.Plan
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class NetWorthBucketsTest {

    private val today = LocalDate.of(2026, 7, 15)
    private fun on(month: Int, day: Int) = LocalDate.of(2026, month, day)

    @Test
    fun aMonthEndAtTheWindowEndIsListedOnce() {
        val plan = NetWorthBuckets.plan(DateRange(on(4, 1), on(6, 30)), today)

        assertEquals(Plan.Days(listOf(on(4, 30), on(5, 31), on(6, 30))), plan)
    }

    @Test
    fun aMonthEndAtTheWindowStartIsKept() {
        val plan = NetWorthBuckets.plan(DateRange(on(4, 30), on(6, 15)), today)

        assertEquals(Plan.Days(listOf(on(4, 30), on(5, 31), on(6, 15))), plan)
    }

    @Test
    fun theThirtyFirstDayWindowStaysDailyAndTheThirtySecondGoesToMonthEnds() {
        val daily = NetWorthBuckets.plan(DateRange(on(6, 1), on(7, 1)), today) as Plan.Days
        val monthly = NetWorthBuckets.plan(DateRange(on(5, 31), on(7, 1)), today) as Plan.Days

        assertEquals(31, daily.dates.size)
        assertEquals(listOf(on(5, 31), on(6, 30), on(7, 1)), monthly.dates)
    }

    @Test
    fun aLongWindowWhoseLastDayIsTodayEndsOnToday() {
        val plan = NetWorthBuckets.plan(DateRange(on(1, 10), on(12, 31)), today) as Plan.Days

        assertEquals(today, plan.dates.last())
        assertEquals(listOf(on(1, 31), on(2, 28), on(3, 31), on(4, 30), on(5, 31), on(6, 30), today), plan.dates)
    }

    @Test
    fun twoPointsAreEnoughAndOneIsNot() {
        assertEquals(Plan.Days(listOf(on(7, 14), on(7, 15))), NetWorthBuckets.plan(DateRange(on(7, 14), on(7, 15)), today))
        assertEquals(
            Plan.NoTrend(NoTrendReason.TOO_FEW_POINTS),
            NetWorthBuckets.plan(DateRange(on(7, 15), on(7, 15)), today),
        )
    }
}
