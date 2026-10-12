package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.format.AmountRole
import org.aristonis.mywallet.ui.format.amountRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/** History reads receipt amounts and saved rates without consulting today's exchange rates. */
class TransactionHistoryDetailsTest {
    private val day = LocalDate.of(2026, 10, 12)
    private val formatter = MoneyFormatter(Locale.US)
    private val lookups = RowLookups(
        accountsById = emptyMap(), categoriesById = emptyMap(),
        currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)),
        baseCurrencyCode = "USD",
    )
    private fun eur(amount: String) = Money.of(amount, "EUR")
    private fun income(rate: BigDecimal? = BigDecimal("1.25")) = Transaction.Income(
        accountId = 1, amount = eur("100"), categoryId = 2, date = day, rateToBase = rate,
    )
    private fun line(position: Int, amount: String, timing: DeductionTiming) = DeductionLine(
        itemId = position.toLong() + 1, position = position, name = "Snapshot $position",
        mode = DeductionMode.PERCENT, value = BigDecimal("10"), valueCurrencyCode = null,
        timing = timing, categoryId = 3, amount = eur(amount),
    )

    @Test fun `income shows gross and every immutable deduction including rounded zero`() {
        val receipt = income().copy(deductions = listOf(
            line(0, "10", DeductionTiming.NOW), line(1, "5", DeductionTiming.LATER),
            line(2, "0", DeductionTiming.LATER),
        ))
        val row = receipt.toRow(lookups, formatter)
        assertEquals(eur("100"), row.amount)
        assertEquals(listOf("Snapshot 0", "Snapshot 1", "Snapshot 2"), row.deductions.map { it.name })
        assertEquals(listOf("10.00 EUR", "5.00 EUR", "0.00 EUR"), row.deductions.map { it.amountDisplay })
        assertEquals(DeductionTiming.LATER, row.deductions[2].timing)
        assertEquals(listOf("12.50 USD", "6.25 USD", "0.00 USD"),
            row.deductions.map { it.baseAmountDisplay })
        assertEquals("125.00 USD", row.countedBaseAmountDisplay)
    }

    @Test fun `expense main value and fee are separate counted amounts`() {
        val row = Transaction.Expense(
            accountId = 1, amount = eur("100"), categoryId = 2, date = day,
            rateToBase = BigDecimal("1.25"), fee = eur("2"), feeCategoryId = 3,
        ).toRow(lookups, formatter)
        assertEquals("100.00 EUR", row.amountDisplay)
        assertEquals("2.00 EUR", row.feeDisplay)
        assertEquals("125.00 USD", row.countedBaseAmountDisplay)
        assertEquals("2.50 USD", row.feeBaseAmountDisplay)
    }

    @Test fun `transfer remains neutral and only its fee has a stored base value`() {
        val row = Transaction.Transfer(
            sourceAccountId = 1, destAccountId = 2, sourceAmount = eur("100"),
            destAmount = Money.of("122.50", "USD"), rateUsed = BigDecimal("1.25"), date = day,
            fee = eur("2"), feeCategoryId = 3, feeRateToBase = BigDecimal("1.30"),
        ).toRow(lookups, formatter)
        assertEquals(AmountRole.NEUTRAL, row.type.amountRole())
        assertNull(row.countedBaseAmountDisplay)
        assertEquals("2.00 EUR", row.feeDisplay)
        assertEquals("2.60 USD", row.feeBaseAmountDisplay)
    }

    @Test fun `missing stored rates and base currency amounts never show an approximation`() {
        assertNull(income(null).toRow(lookups, formatter).countedBaseAmountDisplay)
        assertNull(income().copy(amount = Money.of("100", "USD"))
            .toRow(lookups, formatter).countedBaseAmountDisplay)
        assertNull(Transaction.Borrow(accountId = 1, debtId = 2, amount = eur("100"), date = day)
            .toRow(lookups, formatter).countedBaseAmountDisplay)
    }

    @Test fun `repayment account movement stays neutral while interest has its side and stored rate`() {
        for (side in DebtSide.entries) {
            val row = Transaction.Repayment(
                side = side, accountId = 1, debtId = 2, accountAmount = eur("105"),
                principal = eur("100"), interest = eur("5"), interestCategoryId = 3,
                interestRateToBase = BigDecimal("1.4"), date = day,
            ).toRow(lookups, formatter)
            assertEquals(AmountRole.NEUTRAL, row.type.amountRole())
            assertEquals(eur("5"), row.countedAmount)
            assertEquals(if (side == DebtSide.I_OWE) AmountRole.EXPENSE else AmountRole.INCOME,
                row.countedAmountRole)
            assertEquals("7.00 USD", row.countedBaseAmountDisplay)
        }
    }

    @Test fun `refund has an income sign while its reporting amount reduces expense`() {
        val row = Transaction.Refund(
            accountId = 1, amount = eur("10"), categoryId = 2, date = day,
            rateToBase = BigDecimal("1.25"),
        ).toRow(lookups, formatter)
        assertEquals(TransactionRowType.REFUND, row.type)
        assertEquals(AmountRole.INCOME, row.type.amountRole())
        assertEquals(eur("-10"), row.countedAmount)
        assertEquals("12.50 USD", row.countedBaseAmountDisplay)
    }
}
