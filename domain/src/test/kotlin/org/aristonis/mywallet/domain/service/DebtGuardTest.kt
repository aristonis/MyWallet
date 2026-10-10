package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the two checks a debt write relies on: which debts a set of legs touches, and that no debt
 * is ever left owing less than nothing.
 */
class DebtGuardTest {

    private val debt = Debt(id = 3, counterparty = "Ali", side = DebtSide.I_OWE, currencyCode = "USD")

    private fun usd(amount: String) = Money.of(amount, "USD")

    private fun leg(target: LegTarget, role: LegRole = LegRole.DEBT, position: Int = 0) =
        Leg(role, position, target, usd("10"))

    @Test
    fun zeroRemainingPasses() {
        DebtGuard.check(debt, usd("0"))
    }

    @Test
    fun positiveRemainingPasses() {
        DebtGuard.check(debt, usd("150.25"))
    }

    @Test
    fun belowZeroThrowsDebtBelowZeroNamingTheCounterpartyAndAmount() {
        try {
            DebtGuard.check(debt, usd("-200"))
            fail("expected DebtBelowZero")
        } catch (e: WalletException.DebtBelowZero) {
            assertEquals("Ali", e.counterparty)
            assertEquals(usd("200"), e.amount)
        }
    }

    @Test
    fun counterpartyIsReportedAsTypedNotAsTheKey() {
        val typed = debt.copy(counterparty = "  ALI Baba ")
        try {
            DebtGuard.check(typed, usd("-1"))
            fail("expected DebtBelowZero")
        } catch (e: WalletException.DebtBelowZero) {
            assertEquals("ALI Baba", e.counterparty)
        }
    }

    @Test
    fun touchedDebtIdsPicksOnlyDebtTargets() {
        val legs = listOf(
            leg(LegTarget.Account(1), LegRole.ACCOUNT),
            leg(LegTarget.Debt(3, DebtSide.I_OWE)),
            leg(LegTarget.Category(9, null), LegRole.MAIN),
        )

        assertEquals(setOf(3L), DebtGuard.touchedDebtIds(emptyList(), legs))
        assertEquals(setOf(3L), DebtGuard.touchedDebtIds(legs, emptyList()))
    }

    @Test
    fun touchedDebtIdsDeduplicatesAndKeepsEveryDistinctDebt() {
        val old = listOf(
            leg(LegTarget.Debt(3, DebtSide.I_OWE)),
            leg(LegTarget.Debt(3, DebtSide.I_OWE), LegRole.DEDUCTION_DEBT, 1),
        )
        val new = listOf(
            leg(LegTarget.Debt(3, DebtSide.I_OWE)),
            leg(LegTarget.Debt(5, DebtSide.OWED_TO_ME), position = 2),
        )

        assertEquals(setOf(3L, 5L), DebtGuard.touchedDebtIds(old, new))
    }

    @Test
    fun touchedDebtIdsIsEmptyWithoutDebtLegs() {
        val accountOnly = listOf(leg(LegTarget.Account(1), LegRole.ACCOUNT))

        assertTrue(DebtGuard.touchedDebtIds(accountOnly, accountOnly).isEmpty())
        assertTrue(DebtGuard.touchedDebtIds(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun movingALoanToAnotherDebtTouchesBothTheOldAndTheNewDebt() {
        val old = listOf(leg(LegTarget.Debt(7, DebtSide.I_OWE)))
        val new = listOf(leg(LegTarget.Debt(9, DebtSide.I_OWE)))

        assertEquals(setOf(7L, 9L), DebtGuard.touchedDebtIds(old, new))
    }

    @Test
    fun aDeleteTouchesTheOldDebts() {
        val old = listOf(
            leg(LegTarget.Debt(7, DebtSide.I_OWE)),
            leg(LegTarget.Debt(9, DebtSide.OWED_TO_ME), position = 1),
        )

        assertEquals(setOf(7L, 9L), DebtGuard.touchedDebtIds(old, emptyList()))
    }

    @Test
    fun checkRefusesARemainingInAnotherCurrencyThanTheDebt() {
        assertThrows(IllegalArgumentException::class.java) {
            DebtGuard.check(debt, Money.of("10", "EUR"))
        }
    }
}
