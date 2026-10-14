package org.aristonis.mywallet.ui.reports.charts

import org.aristonis.mywallet.domain.model.NoTrendReason
import org.aristonis.mywallet.domain.model.TrackingWindow
import java.math.BigDecimal
import java.time.LocalDate

/**
 * One restated end-of-day balance. [balance] is exact, kept for the line's scale; [balanceDisplay]
 * is the same figure formatted for the table and the chart's description.
 */
data class NetWorthPointRow(val date: LocalDate, val balance: BigDecimal, val balanceDisplay: String)

/**
 * What the Net worth body shows. Every value is a restatement of the current ledger at current
 * rates, so the screen labels it that way in every state.
 */
sealed interface NetWorthData {
    data object Loading : NetWorthData

    /**
     * At least two points, oldest first. The line and the exact table are both drawn from [points].
     * [lowestDisplay] and [highestDisplay] label the line; with [direction] (last point against the
     * first) and [crossesZero] (points on both sides of zero) they describe it to a screen reader.
     */
    data class Trend(
        val currencyCode: String,
        val points: List<NetWorthPointRow>,
        val lowestDisplay: String,
        val highestDisplay: String,
        val direction: TrendDirection,
        val crossesZero: Boolean,
    ) : NetWorthData {
        init {
            require(points.size >= MIN_TREND_POINTS) { "a trend needs at least $MIN_TREND_POINTS points" }
        }

        /** The window's last point: its end, or today when the window runs past today. */
        val latest: NetWorthPointRow get() = points.last()
    }

    /** No line for this window, for [reason]; the exact balance now instead. */
    data class CurrentOnly(val currencyCode: String, val balanceDisplay: String, val reason: NoTrendReason) : NetWorthData

    /** A rate a point needs is missing: no converted value at all, and a way to Exchange rates. */
    data class MissingRate(val currencyCode: String) : NetWorthData

    /** The window needs more dated records than the bounded read takes: no line, no partial table. */
    data object TooManyLegs : NetWorthData

    private companion object {
        const val MIN_TREND_POINTS = 2
    }
}

/**
 * The Net worth screen: its window and what to show for it. [loadFailed] hides every figure.
 * [isUpdating] is true right after the window changes: [window] is already the new one, and [data]
 * still belongs to the previous window until the new read answers.
 */
data class NetWorthUiState(
    val window: TrackingWindow,
    val data: NetWorthData = NetWorthData.Loading,
    val loadFailed: Boolean = false,
    val isUpdating: Boolean = false,
)

/** Whether a trend ends higher, lower, or level with where it starts. */
enum class TrendDirection { UP, DOWN, FLAT }
