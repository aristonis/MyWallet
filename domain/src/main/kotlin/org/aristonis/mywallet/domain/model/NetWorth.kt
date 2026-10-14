package org.aristonis.mywallet.domain.model

import java.time.LocalDate

/** The net worth restated for the end of [date], in the base currency. */
data class NetWorthPoint(val date: LocalDate, val balance: Money)

/**
 * The net worth over a Tracking window, restated from the ledger as it is now at today's rates. It
 * is not a record of what was true on each day: opening balances, archive flags, edited entries and
 * rates all act on earlier points too.
 */
sealed interface NetWorthSeries {

    /** At least two points, oldest first, the last one on the day the window ends (or today). */
    data class Trend(val points: List<NetWorthPoint>) : NetWorthSeries

    /** The exact balance now, with the reason there is no line to draw. */
    data class CurrentOnly(val balance: Money, val reason: NoTrendReason) : NetWorthSeries

    /** A rate a point needs is not stored, so no value is shown. */
    data class MissingRate(val currencyCode: String) : NetWorthSeries

    /** The dated legs the window needs are more than [MAX_CHART_LEGS], so no line is built. */
    data object TooManyLegs : NetWorthSeries
}

enum class NoTrendReason {
    /** The window starts more than 12 calendar months before today, or covers all time. */
    START_TOO_OLD,

    /** The window leaves fewer than two points on or before today. */
    TOO_FEW_POINTS,
}

/** The most account and debt legs one series may read; above it the series is unavailable. */
const val MAX_CHART_LEGS = 50_000
