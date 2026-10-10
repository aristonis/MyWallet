package org.aristonis.mywallet.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountTest {

    private fun money(a: String, c: String = "USD") = Money.of(a, c)

    @Test
    fun holdsBalanceInOwnCurrency() {
        val acc = Account(
            name = "Cash",
            typeKey = "cash",
            currencyCode = "USD",
            openingBalance = money("100"),
        )
        assertEquals("USD", acc.currencyCode)
        assertEquals(money("100"), acc.openingBalance)
    }

    @Test(expected = IllegalArgumentException::class)
    fun openingBalanceCurrencyMustMatch() {
        Account(
            name = "Cash",
            typeKey = "cash",
            currencyCode = "USD",
            openingBalance = money("100", "EUR"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankName_throws() {
        Account(name = "", typeKey = "cash", currencyCode = "USD", openingBalance = money("0"))
    }

    private fun creditAccount(limit: Money?) = Account(
        name = "Visa credit", typeKey = "credit_card", currencyCode = "USD",
        openingBalance = money("0"), creditLimit = limit,
    )

    @Test
    fun noCreditLimitByDefault() {
        val acc = Account(name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = money("0"))
        assertNull(acc.creditLimit)
    }

    @Test
    fun positiveCreditLimitIsAccepted() {
        assertEquals(money("2000"), creditAccount(money("2000")).creditLimit)
    }

    @Test(expected = IllegalArgumentException::class)
    fun creditLimitInAnotherCurrencyIsRefused() {
        creditAccount(money("2000", "EUR"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroCreditLimitIsRefused() {
        creditAccount(money("0"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeCreditLimitIsRefused() {
        creditAccount(money("-100"))
    }
}
