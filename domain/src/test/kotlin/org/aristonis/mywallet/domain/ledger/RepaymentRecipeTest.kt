package org.aristonis.mywallet.domain.ledger

import org.aristonis.mywallet.domain.model.DebtSide.I_OWE
import org.aristonis.mywallet.domain.model.DebtSide.OWED_TO_ME
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Repayments as stored legs: what each side writes, what the reader refuses, and the examples. */
class RepaymentRecipeTest : DebtRecipeFixtures() {

    @Test
    fun repaymentPayingTakesMoneyOutShrinksTheDebtAndBooksInterestPaid() {
        val parts = repaymentPaying.toParts()

        assertEquals(EntryHeader(6, "REPAYMENT", day, null, pairRate = null), parts.header)
        assertEquals(
            listOf(
                account(1, usd("-450")),
                debt(10, I_OWE, usd("-400")),
                category(LegRole.INTEREST_PAID, 7, null, usd("50"), BigDecimal.ONE),
            ),
            parts.legs,
        )
        assertRoundTrip(repaymentPaying)
    }

    @Test
    fun repaymentReceivingTakesMoneyInShrinksTheDebtAndBooksInterestReceived() {
        val parts = repaymentReceiving.toParts()

        assertEquals(
            listOf(
                account(1, usd("450")),
                debt(11, OWED_TO_ME, usd("-400")),
                category(LegRole.INTEREST_RECEIVED, 8, 9, usd("50"), BigDecimal.ONE),
            ),
            parts.legs,
        )
        assertRoundTrip(repaymentReceiving)
    }

    @Test
    fun aRepaymentOfInterestOnlyStillWritesTheDebtLegOfZero() {
        val entry = repaymentPaying.copy(id = 11, accountAmount = usd("50"), principal = usd("0"))

        val parts = entry.toParts()

        assertEquals(
            listOf(
                account(1, usd("-50")),
                debt(10, I_OWE, usd("0")),
                category(LegRole.INTEREST_PAID, 7, null, usd("50"), BigDecimal.ONE),
            ),
            parts.legs,
        )
        assertRoundTrip(entry)
    }

    @Test
    fun aRepaymentWithoutInterestWritesNoInterestLeg() {
        val entry = Transaction.Repayment(
            id = 12, side = OWED_TO_ME, accountId = 1, debtId = 11, accountAmount = usd("400"), principal = usd("400"), date = day,
        )

        val parts = entry.toParts()

        assertEquals(listOf(account(1, usd("400")), debt(11, OWED_TO_ME, usd("-400"))), parts.legs)
        assertRoundTrip(entry)
    }

    @Test
    fun aCrossCurrencyRepaymentKeepsThePairRateOnTheHeaderAndEachLegInItsOwnCurrency() {
        val parts = repaymentCrossCurrency.toParts()

        assertEquals(BigDecimal("0.0001"), parts.header.pairRate)
        assertEquals(
            listOf(
                account(2, syp("-1300000")),
                debt(10, I_OWE, usd("-125")),
                category(LegRole.INTEREST_PAID, 7, null, syp("50000"), BigDecimal("0.00008")),
            ),
            parts.legs,
        )
        assertRoundTrip(repaymentCrossCurrency)
    }

    @Test
    fun aRepaymentWhoseInterestRoleDoesNotMatchItsSideIsUnreadable() {
        assertUnreadable(withRole(repaymentPaying.toParts(), LegRole.INTEREST_PAID, LegRole.INTEREST_RECEIVED))
        assertUnreadable(withRole(repaymentReceiving.toParts(), LegRole.INTEREST_RECEIVED, LegRole.INTEREST_PAID))
    }

    @Test
    fun aSameCurrencyRepaymentWhosePartsDoNotAddUpIsUnreadable() {
        val parts = repaymentPaying.toParts()
        val tooMuchInterest = parts.copy(
            legs = parts.legs.map { if (it.role == LegRole.INTEREST_PAID) it.copy(amount = usd("60")) else it },
        )
        assertUnreadable(tooMuchInterest)
    }

    @Test
    fun aSameCurrencyRepaymentWithoutInterestWhosePrincipalDiffersFromTheAccountLegIsUnreadable() {
        val entry = Transaction.Repayment(
            id = 12, side = I_OWE, accountId = 1, debtId = 10, accountAmount = usd("400"), principal = usd("400"), date = day,
        )
        val parts = entry.toParts()
        assertUnreadable(
            parts.copy(legs = parts.legs.map { if (it.role == LegRole.DEBT) it.copy(amount = usd("-350")) else it }),
        )
    }

    @Test
    fun aPairRateOnASameCurrencyRepaymentIsUnreadable() {
        val parts = repaymentPaying.toParts()
        assertUnreadable(parts.copy(header = parts.header.copy(pairRate = BigDecimal.ONE)))
    }

    @Test
    fun aCrossCurrencyRepaymentNeedsItsPairRate() {
        val parts = repaymentCrossCurrency.toParts()
        assertUnreadable(parts.copy(header = parts.header.copy(pairRate = null)))
        assertUnreadable(parts.copy(header = parts.header.copy(pairRate = BigDecimal.ZERO)))
    }

    @Test
    fun aCrossCurrencyRepaymentKeepsItsPrincipalAsSavedWithoutRecomputingIt() {
        // Paying a debt off in full is priced from the debt side: the debt drops by exactly what
        // remained and the account amount is derived at the pair rate, so the two need not
        // multiply back to each other. The reader keeps what was saved.
        val payTheRest = repaymentCrossCurrency.copy(principal = usd("125.03"))

        assertRoundTrip(payTheRest)
    }

    @Test
    fun aRepaymentWhoseDebtLegGrowsOrWhoseAccountLegPointsTheWrongWayIsUnreadable() {
        val parts = repaymentPaying.toParts()
        assertUnreadable(parts.copy(legs = parts.legs.map { if (it.role == LegRole.DEBT) it.copy(amount = usd("400")) else it }))
        assertUnreadable(parts.copy(legs = parts.legs.map { if (it.role == LegRole.ACCOUNT) it.copy(amount = usd("450")) else it }))
    }

    @Test
    fun aRepaymentWhoseDebtSideFlipsAgainstItsAccountSignIsUnreadable() {
        assertUnreadable(withDebtSide(repaymentPaying.toParts(), OWED_TO_ME))
        assertUnreadable(withDebtSide(repaymentReceiving.toParts(), I_OWE))
    }

    @Test
    fun aRepaymentThatMovesNothingIsUnreadable() {
        // A zero debt leg and no interest leg: the save never writes this, so a stored one is damaged.
        val parts = repaymentCrossCurrency.toParts()
        val nothingMoved = parts.copy(
            legs = parts.legs
                .filter { it.role != LegRole.INTEREST_PAID }
                .map { if (it.role == LegRole.DEBT) it.copy(amount = usd("0")) else it },
        )

        assertUnreadable(nothingMoved)
    }

    @Test
    fun aRepaymentWithBothInterestRolesIsUnreadable() {
        val parts = repaymentPaying.toParts()
        val interest = parts.legs.single { it.role == LegRole.INTEREST_PAID }
        val both = parts.copy(legs = parts.legs + interest.copy(role = LegRole.INTEREST_RECEIVED))

        assertUnreadable(both)
    }

    @Test
    fun aRepaymentWhoseInterestIsInAnotherCurrencyThanItsAccountIsUnreadable() {
        val parts = repaymentPaying.toParts()
        val otherCurrency = parts.copy(
            legs = parts.legs.map { if (it.role == LegRole.INTEREST_PAID) it.copy(amount = syp("50")) else it },
        )

        assertUnreadable(otherCurrency)
    }

    @Test
    fun theExamplesIncludeACollectedCrossCurrencyRepaymentThatRoundTrips() {
        val repayments = examples()

        val collected = repayments.filter { it.side == OWED_TO_ME && it.pairRate != null }

        assertTrue("collected cross-currency example", collected.isNotEmpty())
        collected.forEach { assertRoundTrip(it) }
    }

    @Test
    fun everyRepaymentExampleRoundTrips() {
        examples().forEach { assertRoundTrip(it) }
    }

    @Test
    fun theRepaymentExamplesCoverBothSidesAndEveryShape() {
        val repayments = examples()

        assertEquals(setOf(I_OWE, OWED_TO_ME), repayments.map { it.side }.toSet())
        assertTrue("interest only", repayments.any { it.principal.isZero })
        assertTrue("no interest", repayments.any { it.interest == null })
        assertTrue("cross currency", repayments.any { it.pairRate != null })
    }

    @Suppress("UNCHECKED_CAST")
    private fun examples(): List<Transaction.Repayment> =
        (EntryRecipes.forKind("REPAYMENT")!! as EntryRecipe<Transaction>).examples().map { it as Transaction.Repayment }
}
