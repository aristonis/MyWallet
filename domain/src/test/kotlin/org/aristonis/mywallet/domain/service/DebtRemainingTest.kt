package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.DebtLeg
import org.junit.Assert.assertEquals
import org.junit.Test

class DebtRemainingTest {

    private fun leg(debtId: Long, amount: String, side: DebtSide = DebtSide.I_OWE, currency: String = "USD") =
        DebtLeg(debtId, side, Money.of(amount, currency))

    @Test
    fun noLegsGiveNoDebts() {
        assertEquals(emptyMap<Long, DebtBalance>(), DebtRemaining.byDebt(emptyList()))
    }

    @Test
    fun theLegsOfOneDebtAreSummed() {
        val result = DebtRemaining.byDebt(listOf(leg(1, "500"), leg(1, "-180.50"), leg(1, "20")))

        assertEquals(mapOf(1L to DebtBalance(DebtSide.I_OWE, Money.of("339.50", "USD"))), result)
    }

    @Test
    fun eachDebtIdGetsOneEntryWithItsOwnSideAndCurrency() {
        val result = DebtRemaining.byDebt(
            listOf(
                leg(1, "100"),
                leg(2, "75", DebtSide.OWED_TO_ME, "EUR"),
                leg(1, "-40"),
            ),
        )

        assertEquals(
            mapOf(
                1L to DebtBalance(DebtSide.I_OWE, Money.of("60", "USD")),
                2L to DebtBalance(DebtSide.OWED_TO_ME, Money.of("75", "EUR")),
            ),
            result,
        )
    }

    @Test
    fun aNegativeRemainingIsKept() {
        val result = DebtRemaining.byDebt(listOf(leg(3, "100"), leg(3, "-130")))

        assertEquals(Money.of("-30", "USD"), result.getValue(3L).remaining)
    }

    @Test
    fun aSettledDebtIsKeptAtZero() {
        val result = DebtRemaining.byDebt(listOf(leg(4, "80"), leg(4, "-80")))

        assertEquals(Money.of("0", "USD"), result.getValue(4L).remaining)
    }
}
