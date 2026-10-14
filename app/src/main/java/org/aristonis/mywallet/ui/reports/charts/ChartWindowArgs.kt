package org.aristonis.mywallet.ui.reports.charts

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType
import androidx.navigation.navArgument
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.window.readWindow
import org.aristonis.mywallet.ui.window.writeWindow
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Carries the Tracking window to a chart screen as optional route arguments, so the chart opens on
 * the dates Tracking was showing. Dates travel as ISO strings (`2026-10-13`): URL-safe, readable in a
 * route, and parsed without a locale.
 *
 * The integrator appends [QUERY_PATTERN] to the chart route, registers [navArguments], and navigates
 * with `route + query(window)`. Arguments that are missing or do not read as a window are ignored and
 * the screen starts on its default window.
 */
object ChartWindowArgs {
    const val KIND = "windowKind"
    const val PERIOD = "windowPeriod"
    const val ANCHOR = "windowAnchor"
    const val START = "windowStart"
    const val END = "windowEnd"

    private const val KIND_PERIOD = "period"
    private const val KIND_CUSTOM = "custom"

    /** The optional query a chart route registers after its path. */
    const val QUERY_PATTERN =
        "?$KIND={$KIND}&$PERIOD={$PERIOD}&$ANCHOR={$ANCHOR}&$START={$START}&$END={$END}"

    /** Every argument is an optional string; absent ones arrive as null. */
    val navArguments: List<NamedNavArgument> = listOf(KIND, PERIOD, ANCHOR, START, END).map { name ->
        navArgument(name) {
            type = NavType.StringType
            nullable = true
            defaultValue = null
        }
    }

    /** The query that opens a chart route on [window]. */
    fun query(window: TrackingWindow): String = when (window) {
        is TrackingWindow.Period -> "?$KIND=$KIND_PERIOD&$PERIOD=${window.period.name}&$ANCHOR=${window.anchor}"
        is TrackingWindow.Custom -> "?$KIND=$KIND_CUSTOM&$START=${window.range.start}&$END=${window.range.endInclusive}"
    }

    /**
     * The window [read] describes, or null when the arguments are absent or do not form a window (an
     * unknown period, a date that does not parse, a reversed range).
     */
    fun decode(read: (String) -> Any?): TrackingWindow? = when (read(KIND)) {
        KIND_PERIOD -> {
            val period = TrackingPeriod.entries.firstOrNull { it.name == read(PERIOD) }
            val anchor = dateOf(read(ANCHOR))
            if (period == null || anchor == null) null else orNull { TrackingWindow.Period(period, anchor) }
        }
        KIND_CUSTOM -> {
            val start = dateOf(read(START))
            val end = dateOf(read(END))
            if (start == null || end == null || start.isAfter(end)) null else TrackingWindow.Custom(DateRange(start, end))
        }
        else -> null
    }

    private fun dateOf(value: Any?): LocalDate? = (value as? String)?.let { text -> orNull { LocalDate.parse(text) } }

    /** Builds a value, or null when a date is out of range for java.time or the domain rejects it. */
    private inline fun <T> orNull(build: () -> T): T? = try {
        build()
    } catch (e: DateTimeException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}

/**
 * Stores the window passed in the route under [key], where a [org.aristonis.mywallet.ui.window.TrackingWindowHolder]
 * reads it. Only when nothing is stored there yet: after process death the screen's own saved window,
 * which the user may have moved, wins over the window it was first opened with.
 */
fun SavedStateHandle.seedChartWindow(key: String) {
    if (readWindow(key) != null) return
    val passed = ChartWindowArgs.decode { name -> get<Any?>(name) } ?: return
    writeWindow(key, passed)
}
