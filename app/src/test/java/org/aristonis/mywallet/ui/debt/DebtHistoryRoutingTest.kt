package org.aristonis.mywallet.ui.debt

import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DebtHistoryRoutingTest {
    private val day = LocalDate.of(2026, 10, 12)
    private val edited = mutableListOf<Long>()
    private val repaid = mutableListOf<Pair<Long, Long>>()

    private fun open(entry: Transaction) = openHistoryEntry(entry, 5, { edited += it }, { d, r -> repaid += d to r })

    @Test fun aRepaymentOpensTheRepaymentFormWithItsDebt() {
        val usd = { amount: String -> Money.of(amount, "USD") }
        open(Transaction.Repayment(
            id = 11, side = DebtSide.I_OWE, accountId = 1, debtId = 5,
            accountAmount = usd("3"), principal = usd("3"), date = day,
        ))

        assertEquals(listOf(5L to 11L), repaid)
        assertEquals(emptyList<Long>(), edited)
    }

    @Test fun anyOtherEntryOpensTheEntryEditor() {
        open(Transaction.OwedBefore(12, 5, DebtSide.I_OWE, Money.of("10", "USD"), day))

        assertEquals(listOf(12L), edited)
        assertEquals(emptyList<Pair<Long, Long>>(), repaid)
    }
    @Test fun paidLaterExpenseOpensTheEditableExpenseForm() {
        open(Transaction.Expense(
            id = 13, accountId = 1, amount = Money.of("1000", "USD"), categoryId = 2,
            date = day, owed = Money.of("700", "USD"), owedDebtId = 5,
        ))

        assertEquals(listOf(13L), edited)
        assertEquals(emptyList<Pair<Long, Long>>(), repaid)
    }

}
