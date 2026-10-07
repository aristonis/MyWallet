package org.aristonis.mywallet.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * `LocalDate.toString()` gives "2026-08-26". That is ISO — right for storage, and not how anyone
 * reads a date. Both date fields and the history's day headers go through the shared formatter, so
 * the app writes dates one way, in the reader's own arrangement rather than an English one.
 */
@RunWith(AndroidJUnit4::class)
class DateDisplayTest {

    @get:Rule val compose = createComposeRule()

    private val date = LocalDate.of(2026, 8, 26)

    private fun formatted(build: @Composable () -> (LocalDate) -> String): String {
        var result by mutableStateOf("")
        compose.setContent {
            val format = build()
            result = format(date)
        }
        compose.waitForIdle()
        return result
    }

    @Test
    fun aFormFieldNeverShowsTheIsoForm() {
        val shown = formatted { rememberDateFormatter() }

        assertFalse("still ISO: $shown", shown == date.toString())
        assertFalse("still ISO: $shown", shown.startsWith("2026-"))
    }

    @Test
    fun aFormFieldShowsTheDayTheMonthAndTheYear() {
        val shown = formatted { rememberDateFormatter() }

        assertTrue("no day in: $shown", shown.contains("26"))
        assertTrue("no year in: $shown", shown.contains("2026"))
        // A named month, not a number: "8/26" and "26/8" are the same string to two different readers.
        assertTrue("no month name in: $shown", shown.any { it.isLetter() })
    }

    /** A day inside a list of this year's days does not need the year repeated on every header. */
    @Test
    fun aDayHeaderInTheCurrentYearOmitsTheYear() {
        val shown = formatted { rememberDayFormatter(currentYear = 2026) }

        assertFalse("year should be dropped: $shown", shown.contains("2026"))
        assertTrue("no day in: $shown", shown.contains("26"))
    }

    /** A day from another year without one would be read as this year's. */
    @Test
    fun aDayHeaderFromAnotherYearKeepsIt() {
        val shown = formatted { rememberDayFormatter(currentYear = 2027) }

        assertTrue("year should be kept: $shown", shown.contains("2026"))
    }

    @Test
    fun theSkeletonDecidesWhenTheYearAppears() {
        assertEquals(DAY_SKELETON, daySkeletonFor(date, currentYear = 2026))
        assertEquals(DAY_WITH_YEAR_SKELETON, daySkeletonFor(date, currentYear = 2027))
        assertEquals(DAY_WITH_YEAR_SKELETON, daySkeletonFor(date, currentYear = 2025))
    }
}
