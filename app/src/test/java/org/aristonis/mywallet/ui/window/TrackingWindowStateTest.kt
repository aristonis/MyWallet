package org.aristonis.mywallet.ui.window

import androidx.lifecycle.SavedStateHandle
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Saved state outlives the code that wrote it: an update can land between a save and a restore. A
 * window that no longer reads must come back as "none", so the screen opens on its default instead
 * of crashing before the user sees anything.
 */
class TrackingWindowStateTest {

    private val key = "window"

    private fun restored(vararg entries: Pair<String, Any?>) = SavedStateHandle(mapOf(*entries)).readWindow(key)

    @Test
    fun periodRoundTrips() {
        val handle = SavedStateHandle()
        val window = TrackingWindow.Period(TrackingPeriod.WEEK, LocalDate.of(2026, 12, 30))

        handle.writeWindow(key, window)

        assertEquals(window, handle.readWindow(key))
    }

    @Test
    fun customRoundTripsAndReplacesAPeriod() {
        val handle = SavedStateHandle()
        handle.writeWindow(key, TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 7, 15)))
        val custom = TrackingWindow.Custom(DateRange(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 17)))

        handle.writeWindow(key, custom)

        assertEquals(custom, handle.readWindow(key))
    }

    @Test
    fun nothingStoredReadsAsNone() {
        assertNull(SavedStateHandle().readWindow(key))
    }

    @Test
    fun unknownKindReadsAsNone() {
        assertNull(restored("$key.kind" to "fortnight"))
    }

    @Test
    fun unknownPeriodReadsAsNone() {
        assertNull(restored("$key.kind" to "period", "$key.period" to "QUARTER", "$key.anchor" to 20_000L))
    }

    @Test
    fun missingAnchorReadsAsNone() {
        assertNull(restored("$key.kind" to "period", "$key.period" to "MONTH"))
    }

    @Test
    fun missingEndReadsAsNone() {
        assertNull(restored("$key.kind" to "custom", "$key.start" to 20_000L))
    }

    @Test
    fun reversedCustomRangeReadsAsNone() {
        assertNull(restored("$key.kind" to "custom", "$key.start" to 20_010L, "$key.end" to 20_000L))
    }

    @Test
    fun anchorOutsideTheCalendarReadsAsNone() {
        assertNull(restored("$key.kind" to "period", "$key.period" to "MONTH", "$key.anchor" to Long.MAX_VALUE))
    }

    @Test
    fun customEndOutsideTheCalendarReadsAsNone() {
        assertNull(restored("$key.kind" to "custom", "$key.start" to 0L, "$key.end" to Long.MAX_VALUE))
    }
}
