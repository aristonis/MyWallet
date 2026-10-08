package org.aristonis.mywallet.ui.window

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import java.time.LocalDate

/**
 * The dates a screen covers and every way the date bar can change them, in one place so each screen
 * that filters by date moves through windows the same way.
 *
 * A window that holds today "follows" it: an app left open past midnight, or a screen restored days
 * later, keeps showing the period the user is living in rather than a stale one. A window the user
 * moved away from (a past month, a custom range) is a deliberate choice and never moves on its own.
 * Whether the window follows is decided after every change and saved with it, because once a day
 * has passed it can no longer be worked out from the window alone.
 *
 * Today is read on every call, never kept: the holder can outlive the day it was created on.
 * Calls are expected from one thread, the main one, as screen callbacks are.
 *
 * @param key the saved-state key the window is stored under; distinct per screen.
 * @param defaultWindow where the screen starts, and where leaving a custom range returns to.
 */
class TrackingWindowHolder(
    private val savedState: SavedStateHandle,
    private val key: String,
    private val today: TodayProvider,
    private val defaultWindow: (LocalDate) -> TrackingWindow,
) {
    private val followsKey = "$key.followsToday"
    private val current: MutableStateFlow<TrackingWindow>
    private var followsToday: Boolean

    init {
        val now = today.today()
        val restored = savedState.readWindow(key)
        current = MutableStateFlow(restored ?: defaultWindow(now))
        // A window saved without the flag predates it; leaving it where it was is the safe reading.
        followsToday = if (restored == null) holdsDay(current.value, now) else savedState.get<Boolean>(followsKey) == true
    }

    val window: StateFlow<TrackingWindow> = current.asStateFlow()

    /** The date bar's callbacks, each wired to the matching change below. */
    val actions: DateWindowActions = DateWindowActions(
        onSelectPeriod = ::selectPeriod,
        onStep = ::step,
        onJumpTo = ::jumpTo,
        onSelectRange = ::selectRange,
        onClearRange = ::clearRange,
    )

    // Catch up with today only once every property above exists: refreshing reads and writes them.
    init {
        refreshToday()
    }

    /**
     * Switches period, keeping today in view when the current window holds it. A following window
     * is first brought up to today: if the screen stayed open past midnight, switching should start
     * from the period the user is living in, not the stale one still on screen.
     */
    fun selectPeriod(period: TrackingPeriod) {
        refreshToday()
        val now = today.today()
        set(current.value.withPeriod(period, now), now)
    }

    /** Moves to a neighbouring period; a custom range has none, so this leaves it alone. */
    fun step(steps: Long) = set(current.value.stepped(steps), today.today())

    /** Shows the period that holds [anchor]. Leaving a custom range lands on its month. */
    fun jumpTo(anchor: LocalDate) {
        val period = (current.value as? TrackingWindow.Period)?.period ?: TrackingPeriod.MONTH
        set(TrackingWindow.Period(period, anchor), today.today())
    }

    /**
     * Covers exactly the days between [first] and [second]. A range picker reports them in the order
     * they were tapped, and a range must not start after it ends, so they are ordered here.
     */
    fun selectRange(first: LocalDate, second: LocalDate) =
        set(TrackingWindow.Custom(DateRange(minOf(first, second), maxOf(first, second))), today.today())

    /** Leaves a custom range for the screen's starting point. */
    fun clearRange() {
        val now = today.today()
        set(defaultWindow(now), now)
    }

    /**
     * Keeps a following window on the period that holds today. Call it whenever the screen comes
     * back into view. A window today has moved out of is carried onto today's period; a window today
     * has moved into (a month picked ahead of time) starts following from now on. All time always
     * holds today, so it never moves.
     */
    fun refreshToday() {
        val window = current.value
        val now = today.today()
        when {
            holdsDay(window, now) -> if (!followsToday) set(window, now)
            followsToday && window is TrackingWindow.Period -> set(TrackingWindow.Period(window.period, now), now)
        }
    }

    private fun set(window: TrackingWindow, now: LocalDate) {
        followsToday = holdsDay(window, now)
        current.value = window
        savedState.writeWindow(key, window)
        savedState[followsKey] = followsToday
    }

    /** A custom range is the user's own pick, so it never counts as following today. */
    private fun holdsDay(window: TrackingWindow, day: LocalDate): Boolean =
        window is TrackingWindow.Period && day in window.range
}
