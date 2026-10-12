package org.aristonis.mywallet.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class TransactionTest {

    private val today = LocalDate.of(2026, 7, 2)
    private fun usd(a: String) = Money.of(a, "USD")

    @Test
    fun incomeStoresPositiveAmount() {
        val t = Transaction.Income(accountId = 1, amount = usd("10"), categoryId = 2, date = today)
        assertEquals(usd("10"), t.amount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun income_nonPositive_throws() {
        Transaction.Income(accountId = 1, amount = usd("0"), categoryId = 2, date = today)
    }

    @Test(expected = IllegalArgumentException::class)
    fun transfer_sameAccount_throws() {
        Transaction.Transfer(
            sourceAccountId = 1,
            destAccountId = 1,
            sourceAmount = usd("5"),
            destAmount = usd("5"),
            rateUsed = BigDecimal.ONE,
            date = today,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun transfer_zeroRate_throws() {
        Transaction.Transfer(
            sourceAccountId = 1,
            destAccountId = 2,
            sourceAmount = usd("5"),
            destAmount = usd("5"),
            rateUsed = BigDecimal.ZERO,
            date = today,
        )
    }

    @Test
    fun transferHoldsBothAmounts() {
        val t = Transaction.Transfer(
            sourceAccountId = 1,
            destAccountId = 2,
            sourceAmount = usd("5"),
            destAmount = Money.of("4.6", "EUR"),
            rateUsed = BigDecimal("0.92"),
            date = today,
        )
        assertEquals(usd("5"), t.sourceAmount)
        assertEquals(Money.of("4.6", "EUR"), t.destAmount)
    }

    @Test
    fun expenseFeeInAnotherCurrencyIsRefusedByTheModel() {
        assertThrows(IllegalArgumentException::class.java) {
            Transaction.Expense(
                accountId = 1, amount = usd("10"), categoryId = 2, date = today, fee = Money.of("1", "SYP"),
            )
        }
    }

    @Test
    fun transferFeeInAnotherCurrencyIsRefusedByTheModel() {
        assertThrows(IllegalArgumentException::class.java) {
            Transaction.Transfer(
                sourceAccountId = 1, destAccountId = 2, sourceAmount = usd("10"), destAmount = usd("10"),
                rateUsed = BigDecimal.ONE, date = today, fee = Money.of("1", "SYP"),
            )
        }
    }

    /** A draft carries its fee without a category (save resolves it) and a negative fee gets a typed save error. */
    @Test
    fun aDraftFeeNeedsNoCategoryAndItsSignIsLeftToTheSave() {
        val expense = Transaction.Expense(
            accountId = 1, amount = usd("10"), categoryId = 2, date = today, fee = usd("-1"),
        )
        val transfer = Transaction.Transfer(
            sourceAccountId = 1, destAccountId = 2, sourceAmount = usd("10"), destAmount = usd("10"),
            rateUsed = BigDecimal.ONE, date = today, fee = usd("12"),
        )
        assertEquals(usd("-1"), expense.fee)
        assertEquals(usd("12"), transfer.fee)
    }
}
