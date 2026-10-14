package org.aristonis.mywallet.domain.model

/** Principal added and settled on one debt, all in its native currency. */
data class DebtAmounts(val original: Money, val repaid: Money, val writtenOff: Money) {
    companion object {
        fun zero(currencyCode: String): DebtAmounts {
            val zero = Money.zero(currencyCode)
            return DebtAmounts(zero, zero, zero)
        }
    }
}

/** One debt and its complete entry history, newest date first and then newest id first. */
data class DebtDetail(
    val summary: DebtSummary,
    val entries: List<Transaction>,
    val amounts: DebtAmounts,
) {
    val isAmountBreakdownConsistent: Boolean
        get() = amounts.original - amounts.repaid - amounts.writtenOff == summary.remaining
}
