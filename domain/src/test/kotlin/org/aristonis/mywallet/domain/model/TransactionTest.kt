package org.aristonis.mywallet.domain.model

import org.junit.Assert.assertEquals
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
}
