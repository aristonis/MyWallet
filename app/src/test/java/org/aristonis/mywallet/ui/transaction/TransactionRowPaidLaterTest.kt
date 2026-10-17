package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class TransactionRowPaidLaterTest {
    private val formatter = MoneyFormatter(Locale.US)
    private val lookups = RowLookups(emptyMap(), emptyMap(), listOf(Currency("USD", "$", 2)), "USD")
    private fun usd(value: String) = Money.of(value, "USD")
    private fun expense(owed: String?, fee: String? = null) = Transaction.Expense(
        id = 12, accountId = 1, amount = usd("1000"), categoryId = 2,
        date = LocalDate.of(2026, 10, 17), owed = owed?.let(::usd),
        owedDebtId = owed?.let { 5L }, fee = fee?.let(::usd),
    )

    @Test fun `partly deferred expense retains full reporting price and exposes actual account outflow`() {
        val row = expense("700", "3").toRow(lookups.copy(debtNamesById = mapOf(5L to "Shop A")), formatter)

        assertEquals(TransactionRowType.EXPENSE, row.type)
        assertEquals(usd("1000"), row.amount)
        assertEquals(usd("1000"), row.countedAmount)
        assertEquals(usd("700"), row.owedAmount)
        assertEquals("700.00 USD", row.owedAmountDisplay)
        assertEquals("Shop A", row.owedTo)
        assertEquals("300.00 USD", row.paidNowDisplay)
        assertEquals(usd("303"), row.accountMovement)
        assertEquals("303.00 USD", row.accountMovementDisplay)
    }

    @Test fun `fully deferred expense keeps its full price and zero account movement`() {
        val row = expense("1000").toRow(lookups, formatter)

        assertEquals(TransactionRowType.EXPENSE, row.type)
        assertEquals(usd("1000"), row.amount)
        assertEquals(usd("0"), row.accountMovement)
        assertEquals("0.00 USD", row.accountMovementDisplay)
    }

    @Test fun `ordinary expense retains no paid later receipt fields`() {
        val row = expense(null).toRow(lookups, formatter)

        assertEquals(usd("1000"), row.amount)
        assertNull(row.accountMovement)
    }
}
