package org.aristonis.mywallet.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Pins the state a debt shows: hidden when it has no legs, settled at zero, overdue only while
 * something is still owed past its due date, otherwise open. The day is passed in, never read.
 */
class DebtSummaryTest {

    private val today = LocalDate.of(2026, 10, 10)

    private fun debt(due: LocalDate? = null, currency: String = "USD") =
        Debt(id = 1, counterparty = "Ali", side = DebtSide.I_OWE, currencyCode = currency, dueDate = due)

    private fun usd(amount: String) = Money.of(amount, "USD")

    private fun state(due: LocalDate?, remaining: String, hasLegs: Boolean = true) =
        DebtSummary.of(debt(due), usd(remaining), hasLegs, today).state

    @Test
    fun overdueWhenDueYesterdayAndTenRemains() {
        assertEquals(DebtState.OVERDUE, state(today.minusDays(1), "10"))
    }

    @Test
    fun notOverdueOnceNothingRemains() {
        assertEquals(DebtState.SETTLED, state(today.minusDays(1), "0"))
    }

    @Test
    fun dueTodayIsStillOpen() {
        assertEquals(DebtState.OPEN, state(today, "10"))
    }

    @Test
    fun dueInTheFutureIsOpen() {
        assertEquals(DebtState.OPEN, state(today.plusDays(1), "10"))
    }

    @Test
    fun noDueDateWithMoneyRemainingIsOpen() {
        assertEquals(DebtState.OPEN, state(null, "10"))
    }

    @Test
    fun zeroRemainingIsSettled() {
        assertEquals(DebtState.SETTLED, state(null, "0"))
    }

    @Test
    fun noLegsIsHiddenEvenWithAPastDueDate() {
        assertEquals(DebtState.HIDDEN, state(today.minusDays(30), "0", hasLegs = false))
    }

    @Test
    fun summaryKeepsTheDebtAndRemaining() {
        val d = debt()
        val summary = DebtSummary.of(d, usd("10"), true, today)

        assertEquals(d, summary.debt)
        assertEquals(usd("10"), summary.remaining)
    }

    @Test(expected = IllegalArgumentException::class)
    fun remainingInAnotherCurrencyThanTheDebtFails() {
        DebtSummary.of(debt(), Money.of("10", "EUR"), true, today)
    }

    /**
     * The guard and restore keep remaining at zero or more, so a negative should not reach here. If
     * one does, it reads as open; ledger health reports it in a later step.
     */
    @Test
    fun aNegativeRemainingReadsAsOpen() {
        assertEquals(DebtState.OPEN, state(null, "-5"))
        assertEquals(DebtState.OPEN, state(today.minusDays(1), "-5"))
    }
}
