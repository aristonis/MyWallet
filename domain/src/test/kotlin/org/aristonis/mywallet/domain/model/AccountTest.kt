package org.aristonis.mywallet.domain.model

import org.junit.Assert.assertEquals
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
}
