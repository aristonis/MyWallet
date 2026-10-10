package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.domain.model.Transaction
import java.math.BigDecimal

/**
 * What the rate previews are priced from, built the way the add form builds it. A transfer quotes
 * nothing here: its pair is locked and it is re-derived at its own stored rate, so a rate field would
 * change nothing. The entry's own rate is shown for the currency it was recorded in.
 */
internal fun EditTransactionUiState.quoteInput(): RateQuoteInput {
    val entry = loaded
    val sourceCurrency = if (entry is Transaction.Transfer) {
        null
    } else {
        accounts.firstOrNull { it.id == selectedAccountId }?.currencyCode
    }
    return RateQuoteInput(
        fx = fx,
        sourceCurrency = sourceCurrency,
        destCurrency = null,
        isTransfer = false,
        amountInput = amountInput,
        rateInputs = rateInputs,
        keptRates = entry?.keptRate()?.let { (code, rate) -> mapOf(code to rate) }.orEmpty(),
    )
}

/**
 * The currency an income or expense was recorded in and the rate stored with it. Null for a transfer,
 * which quotes nothing here, and for the debt kinds, which open read-only and quote nothing either.
 */
private fun Transaction.keptRate(): Pair<String, BigDecimal?>? = when (this) {
    is Transaction.Income -> amount.currencyCode to rateToBase
    is Transaction.Expense -> amount.currencyCode to rateToBase
    is Transaction.Transfer,
    is Transaction.Borrow,
    is Transaction.Lend,
    is Transaction.OwedBefore,
    is Transaction.CreditPurchase,
    is Transaction.EarnedUnpaid,
    is Transaction.Repayment,
    is Transaction.WriteOff,
    -> null
}
