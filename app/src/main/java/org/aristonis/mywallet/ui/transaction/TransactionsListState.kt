package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.CategoryLabel
import org.aristonis.mywallet.ui.format.AmountRole
import org.aristonis.mywallet.ui.message.UiMessage
import java.time.LocalDate

/** Which kind a display row is — lets the screen pick the sign/icon without re-deriving it. */
enum class TransactionRowType {
    INCOME,
    EXPENSE,
    REFUND,
    TRANSFER,
    BORROW,
    LEND,
    OWED_BEFORE,
    CREDIT_PURCHASE,
    EARNED_UNPAID,

    /** Paying back what I owe: money leaves the account. */
    REPAYMENT_PAID,

    /** Collecting what is owed to me: money comes into the account. */
    REPAYMENT_RECEIVED,

    /** Giving up on money owed to me: the rest is a loss. */
    WRITE_OFF_LOSS,

    /** Being let off what I owe: the rest is income. */
    WRITE_OFF_FORGIVEN,
}

/** A deduction copied from the saved receipt, including amounts that rounded to zero. */
data class TransactionDeductionRow(
    val name: String,
    val timing: DeductionTiming,
    val amount: Money,
    val amountDisplay: String,
    val baseAmountDisplay: String?,
)

/** Exact saved deductions grouped only by due timing for a bounded history-row summary. */
data class TransactionDeductionSummary(
    val timing: DeductionTiming,
    val amount: Money,
    val amountDisplay: String,
    val snapshotCount: Int,
)

/**
 * A display-ready transaction row: ids already resolved to names, amounts pre-formatted per currency
 * + locale in [amountDisplay]/[destAmountDisplay]. The raw [Money] stays too so tests assert on exact
 * values and the screen keeps applying its own +/−/→ sign prefixes. Transfer rows carry BOTH legs.
 *
 * [amount] is the saved gross for income, the principal for expense, the account side of a repayment,
 * and the debt amount of debt kinds. [accountMovement] is the income receipt after NOW deductions.
 * [countedAmount] is the part that counts in Tracking, in the entry's own currency (a
 * repayment's interest, a write-off's amount); null when nothing on the row counts, so the screen
 * shows no Tracking line for it.
 */
data class TransactionRow(
    val id: Long,
    val date: LocalDate,
    val type: TransactionRowType,
    /** Null when the id resolved to nothing — the screen decides how to show a gap. */
    val accountName: String?,
    val destAccountName: String?,
    val categoryLabel: CategoryLabel?,
    val amount: Money,
    val destAmount: Money?,
    val amountDisplay: String,
    val destAmountDisplay: String?,
    val note: String?,
    /** Income receipt fields; [amount] remains the saved gross for ledger and reporting callers. */
    val grossAmount: Money? = null,
    val grossAmountDisplay: String? = null,
    val accountMovement: Money? = null,
    val accountMovementDisplay: String? = null,
    val countedAmount: Money? = null,
    val countedAmountDisplay: String? = null,
    val countedAmountRole: AmountRole? = null,
    val countedBaseAmountDisplay: String? = null,
    val deductions: List<TransactionDeductionRow> = emptyList(),
    val deductionSummaries: List<TransactionDeductionSummary> = emptyList(),
    val feeDisplay: String? = null,
    val feeBaseAmountDisplay: String? = null,
)

/** How a day reads against the device's current date. */
enum class DayRelation { TODAY, YESTERDAY, EARLIER }

/**
 * One day of history. The list is read a day at a time — "what did I spend today" — so the day is
 * part of the structure rather than a field repeated on every row.
 */
data class TransactionSection(
    val date: LocalDate,
    val relation: DayRelation,
    val rows: List<TransactionRow>,
)

/**
 * Everything, before the view-model has said otherwise. All time ignores its anchor, so the day it is
 * pinned to here never reaches the screen.
 */
private val EVERYTHING: TrackingWindow = TrackingWindow.Period(TrackingPeriod.ALL_TIME, LocalDate.MIN)

/** Immutable snapshot the transactions list renders from. */
data class TransactionsUiState(
    val sections: List<TransactionSection> = emptyList(),
    val isLoading: Boolean = true,
    /** The dates the list is narrowed to; the [sections] always belong to this window. */
    val window: TrackingWindow = EVERYTHING,
    /**
     * The date of an entry just saved outside the dates in force, or null. Without a word the entry
     * would simply be missing from the list the user returns to, so the screen offers to show it.
     */
    val savedOutsideWindow: LocalDate? = null,
    /**
     * The window's transactions could not be read. The screen says so instead of listing nothing:
     * an empty list would read as "nothing in these dates", which is a different, wrong statement.
     */
    val loadFailed: Boolean = false,
    /**
     * What to tell the user while [loadFailed]: [UiMessage.DataUnreadable] for a stored row the app
     * cannot read, [UiMessage.Unexpected] for any other failure. Null while nothing failed.
     */
    val loadFailure: UiMessage? = null,
    /**
     * The window holds more entries than the rows shown, which are its newest. The screen asks for
     * the next page when its last row comes into view.
     */
    val hasMore: Boolean = false,
    /** The next page is being read; the rows already shown stay on screen meanwhile. */
    val isLoadingMore: Boolean = false,
) {
    /** Every row, newest first, ignoring day boundaries. Derived so the two can never disagree. */
    val rows: List<TransactionRow> get() = sections.flatMap { it.rows }

    /** How many rows are shown, without building the flat list. */
    val rowCount: Int get() = sections.sumOf { it.rows.size }

    /**
     * Whether the list is unfiltered. An empty list means "nothing recorded yet" only then; under a
     * narrower window it means "nothing in these dates", and the screen must not confuse the two.
     */
    val showsEverything: Boolean
        get() = (window as? TrackingWindow.Period)?.period == TrackingPeriod.ALL_TIME
}
