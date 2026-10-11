package org.aristonis.mywallet.ui.format

import org.aristonis.mywallet.ui.transaction.TransactionRowType

/**
 * U+2212. The keyboard hyphen renders as a stubby dash that sits too high next to tabular digits,
 * and a screen reader announces it as "dash" rather than "minus".
 */
const val MINUS_SIGN = "−"

/** What an amount means to the wallet, independent of which screen is drawing it. */
enum class AmountRole { INCOME, EXPENSE, NEUTRAL }

/** Prefix an already-formatted amount with the sign its role carries. */
fun signedAmount(role: AmountRole, display: String): String = when (role) {
    AmountRole.INCOME -> "+$display"
    AmountRole.EXPENSE -> "$MINUS_SIGN$display"
    AmountRole.NEUTRAL -> display
}

/**
 * A transfer is neutral: the money moved between the user's own accounts, so the wallet is no richer
 * or poorer and a red or green amount would claim otherwise. Borrowing, lending and an opening
 * balance are neutral for the same reason: money and what is owed move together. A purchase on
 * credit is an expense and income earned but unpaid is income, both counted when they happen.
 *
 * A repayment, paid or received, is neutral too: the principal moves money and the debt together,
 * and only the interest counts, which its row shows on its own line. A write-off counts in full, so
 * a loss reads as an expense and a forgiven debt as income.
 */
fun TransactionRowType.amountRole(): AmountRole = when (this) {
    TransactionRowType.INCOME,
    // It returns money to the account. Its distinct REFUND kind keeps it out of income reporting.
    TransactionRowType.REFUND,
    TransactionRowType.EARNED_UNPAID,
    TransactionRowType.WRITE_OFF_FORGIVEN,
    -> AmountRole.INCOME
    TransactionRowType.EXPENSE,
    TransactionRowType.CREDIT_PURCHASE,
    TransactionRowType.WRITE_OFF_LOSS,
    -> AmountRole.EXPENSE
    TransactionRowType.TRANSFER,
    TransactionRowType.BORROW,
    TransactionRowType.LEND,
    TransactionRowType.OWED_BEFORE,
    TransactionRowType.REPAYMENT_PAID,
    TransactionRowType.REPAYMENT_RECEIVED,
    -> AmountRole.NEUTRAL
}
