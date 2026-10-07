package org.aristonis.mywallet.ui.format

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How dates are written, in one place.
 *
 * Patterns are asked for rather than spelled out: "d MMM y" is the English order, and a locale that
 * puts the month first, or names its months differently, gets its own arrangement from
 * [DateFormat.getBestDateTimePattern] instead of an English one with translated words in it.
 * `LocalDate.toString()` is ISO — correct, and not how anyone reads a date.
 */

/** A day inside a list of days, where the year is usually redundant. */
internal const val DAY_SKELETON = "dMMM"

/** The same day when it belongs to another year, which the reader does need told. */
internal const val DAY_WITH_YEAR_SKELETON = "dMMMy"

/** A single date the user picked and will check, so it always carries its year. */
internal const val FULL_DATE_SKELETON = "dMMMMy"

/**
 * Which skeleton a list header uses.
 *
 * Most of the history a user scrolls is recent, and repeating "2026" on every header is noise that
 * pushes the day itself out of view — but a date from another year without one is misleading.
 */
internal fun daySkeletonFor(date: LocalDate, currentYear: Int): String =
    if (date.year == currentYear) DAY_SKELETON else DAY_WITH_YEAR_SKELETON

/** Formats a day for a list header; see [daySkeletonFor] for when the year appears. */
@Composable
fun rememberDayFormatter(currentYear: Int): (LocalDate) -> String {
    val locale = currentLocale()
    return remember(locale, currentYear) {
        val withinYear = formatterFor(DAY_SKELETON, locale)
        val withYear = formatterFor(DAY_WITH_YEAR_SKELETON, locale)
        val format: (LocalDate) -> String = { date ->
            val formatter = if (daySkeletonFor(date, currentYear) == DAY_SKELETON) withinYear else withYear
            formatter.format(date)
        }
        format
    }
}

/**
 * Formats the date shown on a form field. Always complete: this is the value the user is about to
 * commit a transaction to, so it is the one place an ambiguous or partial date is worst.
 */
@Composable
fun rememberDateFormatter(): (LocalDate) -> String {
    val locale = currentLocale()
    return remember(locale) {
        val formatter = formatterFor(FULL_DATE_SKELETON, locale)
        val format: (LocalDate) -> String = { date -> formatter.format(date) }
        format
    }
}

@Composable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

private fun formatterFor(skeleton: String, locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

/**
 * U+2068 FIRST STRONG ISOLATE and U+2069 POP DIRECTIONAL ISOLATE.
 *
 * A value dropped into a sentence is reordered by the paragraph around it. "Today · 28 August" in a
 * right-to-left layout, where the month name is Arabic, comes out as "August Today · 28" — the words
 * are all there and the sentence is nonsense. Isolating the value tells the bidi algorithm to lay it
 * out on its own and place the finished run as a unit, which is what keeps the sentence in order.
 */
private const val FIRST_STRONG_ISOLATE = "\u2068"
private const val POP_DIRECTIONAL_ISOLATE = "\u2069"

/** Wraps a substituted value so the sentence around it cannot reorder its parts. */
fun bidiIsolate(value: String): String = FIRST_STRONG_ISOLATE + value + POP_DIRECTIONAL_ISOLATE
