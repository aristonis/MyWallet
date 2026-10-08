package org.aristonis.mywallet.ui.window

import androidx.lifecycle.SavedStateHandle
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * One window state machine serves every screen that filters by date. A window showing "now" keeps
 * showing now as the days pass, even while the app stays in memory; a window the user moved away
 * from stays exactly where they put it.
 */
class TrackingWindowHolderTest {

    private var now = LocalDate.of(2026, 9, 30)
    private val today = TodayProvider { now }
    private val thisMonth: (LocalDate) -> TrackingWindow = { TrackingWindow.Period(TrackingPeriod.MONTH, it) }
    private val allTime: (LocalDate) -> TrackingWindow = { TrackingWindow.Period(TrackingPeriod.ALL_TIME, it) }

    private fun holder(saved: SavedStateHandle = SavedStateHandle(), default: (LocalDate) -> TrackingWindow = thisMonth) =
        TrackingWindowHolder(savedState = saved, key = "window", today = today, defaultWindow = default)

    @Test
    fun startsOnTheScreensDefault() {
        assertEquals(TrackingWindow.Period(TrackingPeriod.MONTH, now), holder().window.value)
        assertEquals(TrackingWindow.Period(TrackingPeriod.ALL_TIME, now), holder(default = allTime).window.value)
    }

    @Test
    fun theCurrentMonthFollowsTodayIntoTheNextMonth() {
        val holder = holder()

        now = LocalDate.of(2026, 10, 2)
        holder.refreshToday()

        assertEquals(TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 10, 2)), holder.window.value)
    }

    @Test
    fun aPastMonthStaysWhereTheUserPutIt() {
        val holder = holder()
        holder.step(-1)

        now = LocalDate.of(2026, 10, 2)
        holder.refreshToday()

        assertEquals(TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 8, 30)), holder.window.value)
    }

    @Test
    fun steppingBackToTheCurrentMonthFollowsTodayAgain() {
        val holder = holder()
        holder.step(-1)
        holder.step(1)

        now = LocalDate.of(2026, 10, 2)
        holder.refreshToday()

        assertEquals(TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 10, 2)), holder.window.value)
    }

    @Test
    fun aCustomRangeNeverMoves() {
        val holder = holder()
        holder.selectRange(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 1))

        now = LocalDate.of(2026, 10, 2)
        holder.refreshToday()

        assertEquals(
            TrackingWindow.Custom(DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 20))),
            holder.window.value,
        )
    }

    @Test
    fun clearingARangeReturnsToTheScreensDefault() {
        val holder = holder(default = allTime)
        holder.selectRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 20))

        holder.clearRange()

        assertEquals(TrackingWindow.Period(TrackingPeriod.ALL_TIME, now), holder.window.value)
    }

    @Test
    fun aRestoredCurrentMonthFollowsTodayAfterProcessDeath() {
        val saved = SavedStateHandle()
        holder(saved).selectPeriod(TrackingPeriod.MONTH)

        now = LocalDate.of(2026, 10, 2)

        assertEquals(TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 10, 2)), holder(saved).window.value)
    }

    @Test
    fun aRestoredPastMonthStaysPutAfterProcessDeath() {
        val saved = SavedStateHandle()
        holder(saved).step(-1)

        now = LocalDate.of(2026, 10, 2)

        assertEquals(TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 8, 30)), holder(saved).window.value)
    }

    @Test
    fun jumpingKeepsThePeriodAndLeavingARangeOpensItsMonth() {
        val holder = holder()
        holder.selectPeriod(TrackingPeriod.YEAR)
        holder.jumpTo(LocalDate.of(2024, 5, 5))
        assertEquals(TrackingWindow.Period(TrackingPeriod.YEAR, LocalDate.of(2024, 5, 5)), holder.window.value)

        holder.selectRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 9))
        holder.jumpTo(LocalDate.of(2026, 3, 3))
        assertEquals(TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 3, 3)), holder.window.value)
    }

    @Test
    fun actionsDriveTheHolder() {
        val holder = holder()

        holder.actions.onStep(-1)
        holder.actions.onSelectPeriod(TrackingPeriod.YEAR)

        // August, then its year: anchored on Aug 1 because today (Sep 30) was outside August.
        assertEquals(TrackingWindow.Period(TrackingPeriod.YEAR, LocalDate.of(2026, 8, 1)), holder.window.value)
    }

    @Test
    fun aWindowSavedWithoutTheFollowFlagStaysPut() {
        // State written before windows could follow today carries no flag; reading it as "follows"
        // could move a window the user chose, so it is left where it was.
        val saved = SavedStateHandle()
        saved.writeWindow("window", TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 9, 10)))

        now = LocalDate.of(2026, 10, 2)

        assertEquals(TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 9, 10)), holder(saved).window.value)
    }

    @Test
    fun allTimeNeverMoves() {
        val holder = holder(default = allTime)
        val before = holder.window.value

        now = LocalDate.of(2027, 1, 1)
        holder.refreshToday()

        assertEquals(before, holder.window.value)
    }

    @Test
    fun switchingPeriodOnAStaleCurrentWindowStartsFromToday() {
        // The screen stayed open past the end of September; nothing called refreshToday yet.
        val holder = holder()
        now = LocalDate.of(2026, 10, 1)

        holder.selectPeriod(TrackingPeriod.WEEK)

        // The week of Oct 1, not the first week of the September still on screen.
        assertEquals(TrackingWindow.Period(TrackingPeriod.WEEK, LocalDate.of(2026, 10, 1)), holder.window.value)
        now = LocalDate.of(2026, 10, 8)
        holder.refreshToday()
        assertEquals(TrackingWindow.Period(TrackingPeriod.WEEK, LocalDate.of(2026, 10, 8)), holder.window.value)
    }

    @Test
    fun aFutureMonthStartsFollowingOnceTodayReachesIt() {
        val holder = holder()
        holder.step(1) // October, picked on Sep 30

        now = LocalDate.of(2026, 10, 15)
        holder.refreshToday()
        now = LocalDate.of(2026, 11, 2)
        holder.refreshToday()

        assertEquals(TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 11, 2)), holder.window.value)
    }
}
