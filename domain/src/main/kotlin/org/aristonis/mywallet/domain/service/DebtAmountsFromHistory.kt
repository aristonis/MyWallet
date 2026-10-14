package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.model.DebtAmounts
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Transaction

/** Classifies the saved history without deriving the authoritative remaining balance. */
object DebtAmountsFromHistory {
    fun calculate(debtId: Long, currencyCode: String, entries: List<Transaction>): DebtAmounts =
        entries.fold(DebtAmounts.zero(currencyCode)) { amounts, entry ->
            when (entry) {
                is Transaction.Borrow -> if (entry.debtId == debtId) {
                    amounts.copy(original = amounts.original + entry.amount)
                } else amounts
                is Transaction.Lend -> if (entry.debtId == debtId) {
                    amounts.copy(original = amounts.original + entry.amount)
                } else amounts
                is Transaction.OwedBefore -> if (entry.debtId == debtId) {
                    amounts.copy(original = amounts.original + entry.amount)
                } else amounts
                is Transaction.CreditPurchase -> if (entry.debtId == debtId) {
                    amounts.copy(original = amounts.original + entry.amount)
                } else amounts
                is Transaction.EarnedUnpaid -> if (entry.debtId == debtId) {
                    amounts.copy(original = amounts.original + entry.amount)
                } else amounts
                is Transaction.Income -> amounts.copy(original = entry.deductions
                    .filter { it.timing == DeductionTiming.LATER && it.debtId == debtId && it.amount.isPositive }
                    .fold(amounts.original) { total, line -> total + line.amount })
                is Transaction.Repayment -> if (entry.debtId == debtId) {
                    amounts.copy(repaid = amounts.repaid + entry.principal)
                } else amounts
                is Transaction.WriteOff -> if (entry.debtId == debtId) {
                    amounts.copy(writtenOff = amounts.writtenOff + entry.amount)
                } else amounts
                is Transaction.Expense, is Transaction.Refund, is Transaction.Transfer -> amounts
            }
        }
}
