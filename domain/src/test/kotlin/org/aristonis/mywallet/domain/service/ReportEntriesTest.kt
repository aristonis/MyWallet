package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class ReportEntriesTest {

    private val day = LocalDate.of(2026, 7, 10)
    private fun usd(a: String) = Money.of(a, "USD")

    @Test
    fun incomeCountsAsIncome() {
        val tx = Transaction.Income(id = 1, accountId = 1, amount = usd("100"), categoryId = 4, subCategoryId = 41, date = day)

        assertEquals(ReportEntry(EntryKind.INCOME, 4, 41, usd("100")), tx.reportEntry())
    }

    @Test
    fun expenseCountsAsExpense() {
        val tx = Transaction.Expense(id = 1, accountId = 1, amount = usd("30"), categoryId = 7, date = day)

        assertEquals(ReportEntry(EntryKind.EXPENSE, 7, null, usd("30")), tx.reportEntry())
    }

    @Test
    fun transferIsNotReported() {
        val tx = Transaction.Transfer(
            id = 1, sourceAccountId = 1, destAccountId = 2,
            sourceAmount = usd("50"), destAmount = usd("50"),
            rateUsed = BigDecimal.ONE, date = day,
        )

        assertNull(tx.reportEntry())
    }
}
