package org.aristonis.mywallet.domain.ledger

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.recipe.ExpenseRecipe
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.service.CurrencyConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class PaidLaterRecipeTest {
    @Test fun partialPaymentWithFeeKeepsTheFullExpenseAndDebt() {
        val entry = expense(owed = "700", fee = "3")
        val parts = ExpenseRecipe.toLegs(entry)
        assertEquals(mapOf(LegRole.ACCOUNT to money("-303"), LegRole.MAIN to money("300"),
            LegRole.PAID_LATER to money("700"), LegRole.DEBT to money("700"), LegRole.FEE to money("3")),
            parts.legs.associate { it.role to it.amount })
        assertEquals(LegTarget.Debt(8, DebtSide.I_OWE), parts.legs.single { it.role == LegRole.DEBT }.target)
        assertEquals(TrackingRule.EXPENSE, ExpenseRecipe.trackingRuleByRole[LegRole.PAID_LATER])
        assertEquals(entry, ExpenseRecipe.fromLegs(parts))
    }

    @Test fun fullyDeferredExpenseKeepsZeroAccountAndMainWithoutFee() {
        val entry = expense(owed = "1000")
        val parts = ExpenseRecipe.toLegs(entry)
        assertEquals(money("0"), parts.legs.single { it.role == LegRole.ACCOUNT }.amount)
        assertEquals(money("0"), parts.legs.single { it.role == LegRole.MAIN }.amount)
        assertFalse(parts.legs.any { it.role == LegRole.FEE })
        assertEquals(entry, ExpenseRecipe.fromLegs(parts))
    }

    @Test fun plainExpenseWithAndWithoutFeeReadsAsBefore() {
        listOf(expense(), expense(fee = "3")).forEach { entry ->
            val parts = ExpenseRecipe.toLegs(entry)
            assertFalse(parts.legs.any { it.role == LegRole.PAID_LATER || it.role == LegRole.DEBT })
            assertEquals(entry, ExpenseRecipe.fromLegs(parts))
        }
    }

    @Test fun malformedPaidLaterShapesAreUnreadable() {
        val parts = storedParts()
        val changes = listOf<(Leg) -> Leg>(
            { if (it.role == LegRole.PAID_LATER) it.copy(amount = money("0")) else it },
            { if (it.role == LegRole.PAID_LATER) it.copy(amount = money("-700")) else it },
            { if (it.role == LegRole.PAID_LATER) it.copy(amount = money("699")) else it },
            { if (it.role == LegRole.PAID_LATER) it.copy(target = LegTarget.Category(99, 7)) else it },
            { if (it.role == LegRole.PAID_LATER) it.copy(target = LegTarget.Category(4, null)) else it },
            { if (it.role == LegRole.PAID_LATER) it.copy(amount = Money.of("700", "EUR")) else it },
            { if (it.role == LegRole.PAID_LATER) it.copy(rateToBase = BigDecimal("2")) else it },
            { if (it.role == LegRole.DEBT) it.copy(target = LegTarget.Debt(8, DebtSide.OWED_TO_ME)) else it },
            { if (it.role == LegRole.MAIN) it.copy(amount = money("-300")) else it },
            { if (it.role == LegRole.ACCOUNT) it.copy(amount = money("-304")) else it },
        )
        changes.forEach { change -> unreadable(parts.copy(legs = parts.legs.map(change))) }
        parts.legs.indices.forEach { missing ->
            unreadable(parts.copy(legs = parts.legs.filterIndexed { index, _ -> index != missing }))
        }
        unreadable(parts.copy(legs = parts.legs + parts.legs.last()))
        unreadable(parts.copy(legs = parts.legs.map { if (it.role == LegRole.DEBT) it.copy(position = 1) else it }))
    }

    @Test fun zeroMainRequiresPaidLaterAndForbidsFees() {
        val zero = ExpenseRecipe.toLegs(expense()).let { parts -> parts.copy(legs = parts.legs.map { it.copy(amount = money("0")) }) }
        unreadable(zero)
        val deferred = storedParts().copy(legs = storedParts().legs.map {
            when (it.role) {
                LegRole.MAIN -> it.copy(amount = money("0"))
                LegRole.ACCOUNT -> it.copy(amount = money("-3"))
                else -> it
            }
        })
        unreadable(deferred)
    }

    @Test fun separateForeignCurrencyLegRoundingDiffersByAtMostOneBaseCent() {
        val entry = expense(owed = "700").copy(amount = Money.of("1000", "EUR"), owed = Money.of("700", "EUR"), rateToBase = BigDecimal("1.000015"))
        val converter = CurrencyConverter("USD", mapOf("EUR" to entry.rateToBase!!), mapOf("USD" to 2))
        val parts = ExpenseRecipe.toLegs(entry)
        val categories = parts.legs.filter { it.target is LegTarget.Category }
        assertEquals(setOf(LegRole.MAIN, LegRole.PAID_LATER), categories.map { it.role }.toSet())
        val split = categories.sumOf { converter.convert(it.amount, "USD").amount }
        val whole = converter.convert(entry.amount, "USD").amount
        assertTrue(split.subtract(whole).abs() <= BigDecimal("0.01"))
        assertEquals(BigDecimal("0.01"), split.subtract(whole).abs())
    }

    private fun storedParts(): EntryParts = EntryParts(EntryHeader(1, "EXPENSE", LocalDate.of(2026, 10, 17), null), listOf(
        Leg(LegRole.ACCOUNT, 0, LegTarget.Account(2), money("-303")),
        Leg(LegRole.MAIN, 0, LegTarget.Category(4, 7), money("300"), BigDecimal.ONE),
        Leg(LegRole.FEE, 0, LegTarget.Category(5, null), money("3"), BigDecimal.ONE),
        Leg(LegRole.PAID_LATER, 0, LegTarget.Category(4, 7), money("700"), BigDecimal.ONE),
        Leg(LegRole.DEBT, 0, LegTarget.Debt(8, DebtSide.I_OWE), money("700")),
    ))

    private fun unreadable(parts: EntryParts) {
        val error = assertThrows(WalletException.UnreadableRow::class.java) { ExpenseRecipe.fromLegs(parts) }
        assertEquals(parts.header.id, error.id)
    }

    private fun expense(owed: String? = null, fee: String? = null) = Transaction.Expense(
        id = 1, accountId = 2, amount = money("1000"), categoryId = 4, subCategoryId = 7,
        date = LocalDate.of(2026, 10, 17), rateToBase = BigDecimal.ONE,
        fee = fee?.let(::money), feeCategoryId = fee?.let { 5L },
        owed = owed?.let(::money), owedDebtId = owed?.let { 8L },
    )
    private fun money(value: String) = Money.of(value, "USD")
}
