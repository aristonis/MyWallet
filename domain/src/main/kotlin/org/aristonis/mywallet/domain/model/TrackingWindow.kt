package org.aristonis.mywallet.domain.model

import org.aristonis.mywallet.domain.service.PeriodRanges
import java.time.LocalDate

/**
 * The span a report covers: either a calendar [Period] around an anchor day, which can be stepped
 * to its neighbours, or a [Custom] range the user picked, which has no natural neighbour.
 */
sealed interface TrackingWindow {
    val range: DateRange

    /** Whether previous / next make sense for this window. */
    val canStep: Boolean

    /**
     * This window [steps] units away (negative = earlier), or this same window when it [canStep] is
     * false. Total on purpose: a stale tap on an arrow the screen has already hidden must be a no-op,
     * not a crash.
     */
    fun stepped(steps: Long): TrackingWindow

    /**
     * Switch to [newPeriod], anchored on [today] when today is inside this window, otherwise on the
     * window's first day.
     *
     * The current anchor is deliberately not reused: month and year stepping clamp it (Mar 31 steps
     * back to Feb 28, then forward to Mar 28), so carrying it into a finer period would land on a day
     * the user never picked. An all-time window always contains today, so it re-anchors there.
     */
    fun withPeriod(newPeriod: TrackingPeriod, today: LocalDate): Period =
        Period(newPeriod, if (today in range) today else range.start)

    /**
     * A calendar period containing [anchor]. The anchor, not the range start, is what moves, so a
     * month stepped from the 31st clamps per java.time and its range still covers the whole month.
     */
    data class Period(val period: TrackingPeriod, val anchor: LocalDate) : TrackingWindow {
        override val range: DateRange = PeriodRanges.of(period, anchor)
        override val canStep: Boolean get() = period != TrackingPeriod.ALL_TIME

        /** The same period [steps] units away (negative = earlier). All time throws: it has no neighbour. */
        fun shifted(steps: Long): Period = copy(anchor = PeriodRanges.shift(period, anchor, steps))

        override fun stepped(steps: Long): TrackingWindow = if (canStep) shifted(steps) else this
    }

    /** A user-picked inclusive range. It is not a calendar unit, so it cannot step. */
    data class Custom(override val range: DateRange) : TrackingWindow {
        override val canStep: Boolean get() = false

        override fun stepped(steps: Long): TrackingWindow = this
    }
}
