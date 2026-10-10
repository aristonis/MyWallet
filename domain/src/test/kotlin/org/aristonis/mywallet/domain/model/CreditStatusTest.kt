package org.aristonis.mywallet.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the owner owes on a credit card. A negative balance is debt to the bank; spending past the
 * limit is allowed (the app records what happened) and is flagged, never refused.
 */
class CreditStatusTest {

    private fun usd(a: String) = Money.of(a, "USD")

    @Test
    fun limit2000_spend450_owes450_and_1550Available() {
        val status = CreditStatus.of(balance = usd("-450"), limit = usd("2000"))

        assertEquals(usd("450"), status.owed)
        assertEquals(usd("1550"), status.available)
        assertFalse(status.overLimit)
    }

    @Test
    fun pastTheLimitIsAllowedAndFlagged() {
        val status = CreditStatus.of(balance = usd("-2100"), limit = usd("2000"))

        assertEquals(usd("2100"), status.owed)
        assertEquals(usd("0"), status.available)
        assertTrue(status.overLimit)
    }

    @Test
    fun exactlyAtTheLimitIsNotOver() {
        val status = CreditStatus.of(balance = usd("-2000"), limit = usd("2000"))

        assertEquals(usd("2000"), status.owed)
        assertEquals(usd("0"), status.available)
        assertFalse(status.overLimit)
    }

    @Test
    fun noLimitShowsOwedOnly() {
        val status = CreditStatus.of(balance = usd("-450"), limit = null)

        assertEquals(usd("450"), status.owed)
        assertNull(status.available)
        assertFalse(status.overLimit)
    }

    @Test
    fun noLimitAndHugeDebtIsNeverOverLimit() {
        val status = CreditStatus.of(balance = usd("-1000000"), limit = null)

        assertFalse(status.overLimit)
    }

    @Test
    fun paidMoreThanOwedOwesNothingAndTheWholeLimitIsAvailable() {
        val status = CreditStatus.of(balance = usd("75"), limit = usd("2000"))

        assertEquals(usd("0"), status.owed)
        assertEquals(usd("2000"), status.available)
        assertFalse(status.overLimit)
    }

    @Test
    fun zeroBalanceOwesNothing() {
        val status = CreditStatus.of(balance = usd("0"), limit = usd("2000"))

        assertEquals(usd("0"), status.owed)
        assertEquals(usd("2000"), status.available)
        assertFalse(status.overLimit)
    }

    @Test
    fun cents_areExact() {
        val status = CreditStatus.of(balance = usd("-1146.04"), limit = usd("2000.00"))

        assertEquals(usd("1146.04"), status.owed)
        assertEquals(usd("853.96"), status.available)
    }

    @Test
    fun owedAndAvailableKeepTheBalanceCurrency() {
        val status = CreditStatus.of(balance = Money.of("-10", "EUR"), limit = Money.of("100", "EUR"))

        assertEquals("EUR", status.owed.currencyCode)
        assertEquals("EUR", status.available?.currencyCode)
    }
}
