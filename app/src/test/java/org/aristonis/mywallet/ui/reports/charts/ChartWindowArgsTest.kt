package org.aristonis.mywallet.ui.reports.charts

import androidx.lifecycle.SavedStateHandle
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.window.readWindow
import org.aristonis.mywallet.ui.window.writeWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * A chart screen opens on the window Tracking was showing. The window travels as route arguments,
 * so it has to survive the trip exactly, and anything that does not read as a window falls back to
 * the screen's own default instead of crashing.
 */
class ChartWindowArgsTest {

    /** The arguments navigation would hand the destination for [query]. */
    private fun argumentsOf(query: String): Map<String, Any?> =
        query.removePrefix("?").split('&').filter { it.isNotEmpty() }.associate { pair ->
            val (name, value) = pair.split('=', limit = 2)
            name to value
        }

    @Test
    fun aPeriodWindow_roundTripsThroughTheRoute() {
        val window = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 10, 13))

        val decoded = ChartWindowArgs.decode(argumentsOf(ChartWindowArgs.query(window))::get)

        assertEquals(window, decoded)
    }

    @Test
    fun aCustomWindow_roundTripsThroughTheRoute() {
        val window = TrackingWindow.Custom(DateRange(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 10, 2)))

        val decoded = ChartWindowArgs.decode(argumentsOf(ChartWindowArgs.query(window))::get)

        assertEquals(window, decoded)
    }

    @Test
    fun theQuery_carriesOnlyArgumentsThePatternDeclares() {
        val query = ChartWindowArgs.query(TrackingWindow.Period(TrackingPeriod.WEEK, LocalDate.of(2026, 10, 13)))

        val declared = ChartWindowArgs.navArguments.map { it.name }.toSet()
        assertTrue(argumentsOf(query).keys.all { it in declared })
        declared.forEach { name -> assertTrue("pattern lacks $name", "{$name}" in ChartWindowArgs.QUERY_PATTERN) }
    }

    @Test
    fun unreadableArguments_decodeToNothing() {
        assertNull(ChartWindowArgs.decode(emptyMap<String, Any?>()::get))
        assertNull(ChartWindowArgs.decode(mapOf(ChartWindowArgs.KIND to "period", ChartWindowArgs.PERIOD to "FORTNIGHT",
            ChartWindowArgs.ANCHOR to "2026-10-13")::get))
        assertNull(ChartWindowArgs.decode(mapOf(ChartWindowArgs.KIND to "custom", ChartWindowArgs.START to "2026-10-13",
            ChartWindowArgs.END to "2026-10-01")::get))
        assertNull(ChartWindowArgs.decode(mapOf(ChartWindowArgs.KIND to "period", ChartWindowArgs.PERIOD to "MONTH",
            ChartWindowArgs.ANCHOR to "13 Oct")::get))
    }

    @Test
    fun seeding_writesThePassedWindowUnderTheScreenKey() {
        val window = TrackingWindow.Period(TrackingPeriod.YEAR, LocalDate.of(2025, 3, 1))
        val savedState = SavedStateHandle(argumentsOf(ChartWindowArgs.query(window)))

        savedState.seedChartWindow(KEY)

        assertEquals(window, savedState.readWindow(KEY))
    }

    @Test
    fun seeding_keepsAWindowTheScreenAlreadySaved() {
        // After process death the screen's own window wins over the arguments it was opened with.
        val opened = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 10, 13))
        val moved = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 8, 13))
        val savedState = SavedStateHandle(argumentsOf(ChartWindowArgs.query(opened)))
        savedState.writeWindow(KEY, moved)

        savedState.seedChartWindow(KEY)

        assertEquals(moved, savedState.readWindow(KEY))
    }

    private companion object {
        const val KEY = "chartWindow"
    }
}
