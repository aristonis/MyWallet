package org.aristonis.mywallet.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyTest {

    @Test
    fun exactDecimalAddition_noFloatDrift() {
        // AC-28: 0.10 + 0.20 == 0.30 exactly
        val sum = Money.of("0.10", "USD") + Money.of("0.20", "USD")
        assertEquals(Money.of("0.30", "USD"), sum)
    }

    @Test
    fun equalityIgnoresScale() {
        assertEquals(Money.of("2.0", "USD"), Money.of("2.00", "USD"))
        assertEquals(Money.of("2.0", "USD").hashCode(), Money.of("2.00", "USD").hashCode())
    }

    @Test
    fun minusSubtracts() {
        assertEquals(Money.of("1.50", "USD"), Money.of("2.00", "USD") - Money.of("0.50", "USD"))
    }

    @Test
    fun unaryMinusNegates() {
        assertEquals(Money.of("-5", "USD"), -Money.of("5", "USD"))
    }

    @Test
    fun compareToOrders() {
        assertTrue(Money.of("1", "USD") < Money.of("2", "USD"))
        assertEquals(0, Money.of("2.0", "USD").compareTo(Money.of("2.00", "USD")))
    }

    @Test
    fun signChecks() {
        assertTrue(Money.of("1", "USD").isPositive)
        assertTrue(Money.zero("USD").isZero)
        assertTrue(Money.of("-1", "USD").isNegative)
    }

    @Test(expected = IllegalArgumentException::class)
    fun plusDifferentCurrency_throws() {
        Money.of("1", "USD") + Money.of("1", "EUR")
    }

    @Test(expected = IllegalArgumentException::class)
    fun minusDifferentCurrency_throws() {
        Money.of("1", "USD") - Money.of("1", "EUR")
    }

    @Test(expected = IllegalArgumentException::class)
    fun compareDifferentCurrency_throws() {
        Money.of("1", "USD").compareTo(Money.of("1", "EUR"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankCurrency_throws() {
        Money.of("1", "")
    }
}
