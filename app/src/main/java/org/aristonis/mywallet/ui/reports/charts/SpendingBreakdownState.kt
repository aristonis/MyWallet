package org.aristonis.mywallet.ui.reports.charts

import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.CategoryLabel
import java.math.BigDecimal

/**
 * One expense category over the window. [amount] is the exact net total in the base currency, kept
 * for the chart's scale; [amountDisplay] is the same figure formatted, for the table and the chart's
 * description. A refund larger than the spending makes [amount] negative.
 */
data class SpendingRow(
    val id: Long,
    val label: CategoryLabel,
    val amount: BigDecimal,
    val amountDisplay: String,
    val previewParts: List<CategoryLabel>,
) {
    /** The category gave back more than it spent in this window: a refund effect, not a share. */
    val isRefundEffect: Boolean get() = amount.signum() < 0
}

/**
 * One bar of the chart, made from one [SpendingRow] and kept in the table's order. [length] is the
 * fraction of the window's positive spending; a category that is not a positive spend has length 0
 * and no bar.
 */
data class SpendingBar(
    val id: Long,
    val label: CategoryLabel,
    val amountDisplay: String,
    val length: Float,
    /** Whole-percent share of the window's positive spending; null for a refund effect or zero. */
    val sharePercent: Int?,
) {
    val isDrawn: Boolean get() = length > 0f
}

/** What the Spending breakdown body shows for the window. */
sealed interface SpendingData {
    data object Loading : SpendingData

    /** A leg in [currencyCode] has no stored rate: no breakdown, no partial total. */
    data class MissingRate(val currencyCode: String) : SpendingData

    /**
     * The window's expense categories, biggest first as the domain orders them. [totalDisplay] is the
     * period's expense total, the same figure Tracking shows, and the exact sum of [rows].
     */
    data class Ready(
        val currencyCode: String,
        val totalDisplay: String,
        val rows: List<SpendingRow>,
    ) : SpendingData {
        val isEmpty: Boolean get() = rows.isEmpty()

        val hasRefundEffect: Boolean get() = rows.any { it.isRefundEffect }

        /** The chart, built from [rows] and nothing else, so the table and the bars cannot disagree. */
        val bars: List<SpendingBar>
            get() {
                val values = rows.map { it.amount }
                val lengths = ChartGeometry.shareLengths(values)
                val shares = ChartGeometry.spendingShares(values)
                return rows.indices.map { index ->
                    val row = rows[index]
                    SpendingBar(row.id, row.label, row.amountDisplay, lengths[index], shares[index])
                }
            }
    }
}

/**
 * The Spending breakdown screen: its window and the numbers for it. [loadFailed] means the window or
 * the ledger could not be read; the screen then shows the failure and no figures. [isUpdating] is
 * true right after the window changes: [window] is already the new one, and [data] still belongs to
 * the previous window until the new read answers.
 */
data class SpendingUiState(
    val window: TrackingWindow,
    val data: SpendingData = SpendingData.Loading,
    val loadFailed: Boolean = false,
    val isUpdating: Boolean = false,
)
