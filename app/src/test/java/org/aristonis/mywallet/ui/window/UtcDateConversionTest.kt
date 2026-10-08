package org.aristonis.mywallet.ui.window

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

/**
 * Material's date pickers hold UTC-midnight millis. Read back in the device zone, that instant is
 * still the previous evening anywhere west of UTC, so every picked day would land one day early.
 * These pin the conversion in zones on both sides of UTC.
 */
class UtcDateConversionTest {

    private val originalZone: TimeZone = TimeZone.getDefault()

    @After
    fun restoreZone() = TimeZone.setDefault(originalZone)

    private val days = listOf(
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 12, 31),
        LocalDate.of(2028, 2, 29),
        LocalDate.of(2026, 3, 8), // a US daylight-saving switch
    )

    private fun assertRoundTripsIn(zone: String) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        days.forEach { day -> assertEquals(day, utcMillisToDate(day.toUtcMillis()!!)) }
    }

    @Test
    fun roundTripsWestOfUtc() = assertRoundTripsIn("America/Los_Angeles")

    @Test
    fun roundTripsFarEastOfUtc() = assertRoundTripsIn("Pacific/Kiritimati")

    @Test
    fun aDayThePickerCannotShowHasNoSeed() {
        assertNull(LocalDate.of(2101, 1, 1).toUtcMillis())
        assertNull(LocalDate.of(1899, 12, 31).toUtcMillis())
    }
}
