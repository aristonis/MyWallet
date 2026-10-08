package org.aristonis.mywallet.ui.format

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
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
internal fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

internal fun formatterFor(skeleton: String, locale: Locale): DateTimeFormatter =
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

/** A whole month, as a reader names it: "August 2026", or the locale's own order and month name. */
internal const val MONTH_YEAR_SKELETON = "yMMMM"

/** A year on its own, in the locale's digits. */
internal const val YEAR_SKELETON = "y"

/** The opening end of a range, whose year the closing end already states. */
private const val RANGE_START_SKELETON = "MMMd"

/** The closing end of a range, which carries the year for both — or either end, across a new year. */
private const val RANGE_END_SKELETON = "yMMMd"

/**
 * Joins the two ends of a range. Punctuation rather than a word, so there is nothing to translate,
 * and each end is isolated so a right-to-left paragraph cannot pull the parts of one date apart.
 */
private const val RANGE_SEPARATOR = " \u2013 "

/**
 * What the date bar calls the span a report covers, or null for all time, whose bounds are a
 * sentinel and not dates anybody chose (the caller shows its own "All time" text instead).
 */
internal fun windowLabel(window: TrackingWindow, locale: Locale): String? = when (window) {
    is TrackingWindow.Custom -> rangeLabel(window.range, locale)
    is TrackingWindow.Period -> when (window.period) {
        TrackingPeriod.DAY -> formatterFor(FULL_DATE_SKELETON, locale).format(window.anchor)
        TrackingPeriod.WEEK -> rangeLabel(window.range, locale)
        TrackingPeriod.MONTH -> formatterFor(MONTH_YEAR_SKELETON, locale).format(window.anchor)
        TrackingPeriod.YEAR -> formatterFor(YEAR_SKELETON, locale).format(window.anchor)
        TrackingPeriod.ALL_TIME -> null
    }
}

private fun rangeLabel(range: DateRange, locale: Locale): String? {
    if (range.isAllTime) return null
    // One day is a date, not a range: "Aug 3 – Aug 3, 2026" would make the reader check both ends.
    if (range.start == range.endInclusive) return formatterFor(RANGE_END_SKELETON, locale).format(range.start)
    // A range that crosses into a new year names both years, or "Dec 29 – Jan 4, 2027" would leave
    // the reader to guess which December.
    val sameYear = range.start.year == range.endInclusive.year
    val startSkeleton = if (sameYear) RANGE_START_SKELETON else RANGE_END_SKELETON
    val start = formatterFor(startSkeleton, locale).format(range.start)
    val end = formatterFor(RANGE_END_SKELETON, locale).format(range.endInclusive)
    return bidiIsolate(start) + RANGE_SEPARATOR + bidiIsolate(end)
}

/** [windowLabel] in the reader's locale, recomputed only when the window or the locale changes. */
@Composable
fun rememberWindowLabel(window: TrackingWindow): String? {
    val locale = currentLocale()
    return remember(window, locale) { windowLabel(window, locale) }
}

/** Formats with one skeleton in the reader's locale; for pickers that name months and years. */
@Composable
internal fun rememberSkeletonFormatter(skeleton: String): (LocalDate) -> String {
    val locale = currentLocale()
    return remember(skeleton, locale) {
        val formatter = formatterFor(skeleton, locale)
        val format: (LocalDate) -> String = { date -> formatter.format(date) }
        format
    }
}
