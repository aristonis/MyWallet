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
 * or poorer and a red or green amount would claim otherwise.
 */
fun TransactionRowType.amountRole(): AmountRole = when (this) {
    TransactionRowType.INCOME -> AmountRole.INCOME
    TransactionRowType.EXPENSE -> AmountRole.EXPENSE
    TransactionRowType.TRANSFER -> AmountRole.NEUTRAL
}
