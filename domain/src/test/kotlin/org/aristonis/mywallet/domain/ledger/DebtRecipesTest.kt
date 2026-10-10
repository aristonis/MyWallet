package org.aristonis.mywallet.domain.ledger

import org.aristonis.mywallet.domain.model.DebtSide.I_OWE
import org.aristonis.mywallet.domain.model.DebtSide.OWED_TO_ME
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The borrow, lend, owed-before, credit-purchase, earned-unpaid and write-off kinds as stored legs,
 * and the registry and tracking rules of all debt kinds. Repayments are in [RepaymentRecipeTest].
 */
class DebtRecipesTest : DebtRecipeFixtures() {

    @Test
    fun borrowIsMoneyIntoTheAccountAndTheDebtGrowing() {
        val parts = borrow.toParts()

        assertEquals(EntryHeader(1, "BORROW", day, "Loan", pairRate = null), parts.header)
        assertEquals(listOf(account(1, usd("5000")), debt(10, I_OWE, usd("5000"))), parts.legs)
        assertRoundTrip(borrow)
    }

    @Test
    fun lendIsMoneyOutOfTheAccountAndTheDebtGrowing() {
        val parts = lend.toParts()

        assertEquals("LEND", parts.header.kind)
        assertEquals(listOf(account(1, usd("-300")), debt(11, OWED_TO_ME, usd("300"))), parts.legs)
        assertRoundTrip(lend)
    }

    @Test
    fun owedBeforeIsOnlyTheDebtLegOnEitherSide() {
        listOf(I_OWE, OWED_TO_ME).forEach { side ->
            val entry = Transaction.OwedBefore(id = 3, debtId = 14, side = side, amount = usd("900"), date = day)

            val parts = entry.toParts()

            assertEquals("OWED_BEFORE", parts.header.kind)
            assertEquals(listOf(debt(14, side, usd("900"))), parts.legs)
            assertRoundTrip(entry)
        }
    }

    @Test
    fun creditPurchaseGrowsWhatIOweAndCountsTheExpenseOnTheMainLeg() {
        val parts = creditPurchase.toParts()

        assertEquals("CREDIT_PURCHASE", parts.header.kind)
        assertEquals(
            listOf(
                debt(12, I_OWE, usd("120.50")),
                category(LegRole.MAIN, 4, 5, usd("120.50"), BigDecimal.ONE),
            ),
            parts.legs,
        )
        assertRoundTrip(creditPurchase)
    }

    @Test
    fun earnedUnpaidGrowsWhatIsOwedToMeAndCountsTheIncomeOnTheMainLeg() {
        val parts = earnedUnpaid.toParts()

        assertEquals("EARNED_UNPAID", parts.header.kind)
        assertEquals(
            listOf(
                debt(13, OWED_TO_ME, usd("800")),
                category(LegRole.MAIN, 1, null, usd("800"), BigDecimal.ONE),
            ),
            parts.legs,
        )
        assertRoundTrip(earnedUnpaid)
    }

    @Test
    fun aWriteOffOfMoneyOwedToMeIsALossOnTheDebtSide() {
        val parts = writeOffLoss.toParts()

        assertEquals("WRITE_OFF", parts.header.kind)
        assertEquals(
            listOf(
                debt(11, OWED_TO_ME, usd("-75")),
                category(LegRole.LOSS, 20, null, usd("75"), BigDecimal.ONE),
            ),
            parts.legs,
        )
        assertRoundTrip(writeOffLoss)
    }

    @Test
    fun aWriteOffOfWhatIOweIsForgivenIncome() {
        val parts = writeOffForgiven.toParts()

        assertEquals(
            listOf(
                debt(10, I_OWE, usd("-60")),
                category(LegRole.FORGIVEN, 21, 22, usd("60"), BigDecimal.ONE),
            ),
            parts.legs,
        )
        assertRoundTrip(writeOffForgiven)
    }

    @Test
    fun everyDebtKindRoundTripsAndRegistersItsRecipe() {
        val all = listOf(
            borrow, lend, creditPurchase, earnedUnpaid, repaymentPaying, repaymentReceiving,
            repaymentCrossCurrency, writeOffLoss, writeOffForgiven,
            Transaction.OwedBefore(id = 3, debtId = 14, side = I_OWE, amount = usd("900"), date = day),
        )
        all.forEach { entry ->
            assertEquals(entry.toParts().header.kind, entry.recipe().kind)
            assertRoundTrip(entry)
        }
    }

    @Test
    fun aDebtLegWhoseSideDoesNotFitItsKindIsUnreadable() {
        assertUnreadable(withDebtSide(borrow.toParts(), OWED_TO_ME))
        assertUnreadable(withDebtSide(lend.toParts(), I_OWE))
        assertUnreadable(withDebtSide(creditPurchase.toParts(), OWED_TO_ME))
        assertUnreadable(withDebtSide(earnedUnpaid.toParts(), I_OWE))
    }

    @Test
    fun aWriteOffWhoseCategoryRoleDoesNotMatchItsSideIsUnreadable() {
        assertUnreadable(withRole(writeOffLoss.toParts(), LegRole.LOSS, LegRole.FORGIVEN))
        assertUnreadable(withRole(writeOffForgiven.toParts(), LegRole.FORGIVEN, LegRole.LOSS))
    }

    @Test
    fun aWriteOffWhoseDebtLegGrowsIsUnreadable() {
        val parts = writeOffLoss.toParts()
        assertUnreadable(parts.copy(legs = parts.legs.map { if (it.role == LegRole.DEBT) it.copy(amount = usd("75")) else it }))
    }

    @Test
    fun aDebtKindWithAMissingLegIsUnreadable() {
        listOf(borrow, lend, creditPurchase, earnedUnpaid, repaymentPaying, writeOffLoss).forEach { entry ->
            val parts = entry.toParts()
            assertUnreadable(parts.copy(legs = parts.legs.drop(1)))
            assertUnreadable(parts.copy(legs = parts.legs.dropLast(1)))
        }
    }

    @Test
    fun eachDebtKindDeclaresHowItsCategoryLegsCount() {
        fun rules(kind: String) = EntryRecipes.forKind(kind)!!.trackingRuleByRole
        assertEquals(emptyMap<LegRole, TrackingRule>(), rules("BORROW"))
        assertEquals(emptyMap<LegRole, TrackingRule>(), rules("LEND"))
        assertEquals(emptyMap<LegRole, TrackingRule>(), rules("OWED_BEFORE"))
        assertEquals(mapOf(LegRole.MAIN to TrackingRule.EXPENSE), rules("CREDIT_PURCHASE"))
        assertEquals(mapOf(LegRole.MAIN to TrackingRule.INCOME), rules("EARNED_UNPAID"))
        assertEquals(
            mapOf(
                LegRole.INTEREST_PAID to TrackingRule.EXPENSE,
                LegRole.INTEREST_RECEIVED to TrackingRule.INCOME,
            ),
            rules("REPAYMENT"),
        )
        assertEquals(
            mapOf(LegRole.LOSS to TrackingRule.EXPENSE, LegRole.FORGIVEN to TrackingRule.INCOME),
            rules("WRITE_OFF"),
        )
    }

    @Test
    fun theRegistryAnswersTheTrackingRuleOfARoleOnAKind() {
        assertEquals(TrackingRule.EXPENSE, EntryRecipes.trackingRule("CREDIT_PURCHASE", LegRole.MAIN))
        assertEquals(TrackingRule.INCOME, EntryRecipes.trackingRule("EARNED_UNPAID", LegRole.MAIN))
        assertEquals(TrackingRule.EXPENSE, EntryRecipes.trackingRule("REPAYMENT", LegRole.INTEREST_PAID))
        assertEquals(TrackingRule.INCOME, EntryRecipes.trackingRule("REPAYMENT", LegRole.INTEREST_RECEIVED))
        assertEquals(TrackingRule.EXPENSE, EntryRecipes.trackingRule("WRITE_OFF", LegRole.LOSS))
        assertEquals(TrackingRule.INCOME, EntryRecipes.trackingRule("WRITE_OFF", LegRole.FORGIVEN))
        assertNull(EntryRecipes.trackingRule("BORROW", LegRole.MAIN))
        assertNull(EntryRecipes.trackingRule("REPAYMENT", LegRole.MAIN))
    }

    @Test
    fun onlyOwedBeforeOpensBalances() {
        assertTrue(EntryRecipes.forKind("OWED_BEFORE")!!.opensBalances)
        listOf("BORROW", "LEND", "CREDIT_PURCHASE", "EARNED_UNPAID", "REPAYMENT", "WRITE_OFF").forEach {
            assertFalse(it, EntryRecipes.forKind(it)!!.opensBalances)
        }
    }

    @Test
    fun theRegistryHoldsAllTenKindsInAStableOrder() {
        val kinds = listOf(
            "INCOME", "EXPENSE", "TRANSFER", "BORROW", "LEND", "OWED_BEFORE",
            "CREDIT_PURCHASE", "EARNED_UNPAID", "REPAYMENT", "WRITE_OFF",
        )
        assertEquals(kinds, EntryRecipes.all.map { it.kind })
        kinds.forEach { assertNotNull(it, EntryRecipes.forKind(it)) }
    }

    @Test
    fun aWriteOffWhoseDebtSideFlipsAgainstItsRoleIsUnreadable() {
        assertUnreadable(withDebtSide(writeOffLoss.toParts(), I_OWE))
        assertUnreadable(withDebtSide(writeOffForgiven.toParts(), OWED_TO_ME))
    }

    @Suppress("UNCHECKED_CAST")
    private fun examplesOf(kind: String): List<Transaction> =
        (EntryRecipes.forKind(kind)!! as EntryRecipe<Transaction>).examples()

    @Test
    fun theExamplesCoverEveryDebtKindAndBothSides() {
        listOf("BORROW", "LEND", "OWED_BEFORE", "CREDIT_PURCHASE", "EARNED_UNPAID", "REPAYMENT", "WRITE_OFF")
            .forEach { assertTrue(it, examplesOf(it).isNotEmpty()) }
        val owedBefore = examplesOf("OWED_BEFORE").map { (it as Transaction.OwedBefore).side }.toSet()
        assertEquals(setOf(I_OWE, OWED_TO_ME), owedBefore)
        val writeOffs = examplesOf("WRITE_OFF").map { (it as Transaction.WriteOff).side }.toSet()
        assertEquals(setOf(I_OWE, OWED_TO_ME), writeOffs)
    }
}
