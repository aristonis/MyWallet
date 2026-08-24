package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.CategoryTotal
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeCurrencyRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeRateRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeSettingsRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeTransactionRepository
import org.aristonis.mywallet.domain.usecase.fake.fakeFx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ComputeCategoryBreakdownTest {

    private val julRef = LocalDate.of(2026, 7, 15)
    private fun usd(a: String) = Money.of(a, "USD")
    private fun eur(a: String) = Money.of(a, "EUR")

    // Base USD; EUR known but unrated (empty FakeRateRepository) — the "missing rate" condition.
    private fun usecase(transactions: List<Transaction>) = ComputeCategoryBreakdown(
        transactions = FakeTransactionRepository(transactions),
        fx = fakeFx(currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)), base = "USD"),
    )

    /** Unwrap the happy-path result; fails loudly if the breakdown was MissingRate. */
    private fun CategoryBreakdownResult.totals(): List<CategoryTotal> =
        (this as CategoryBreakdownResult.Resolved).totals

    @Test
    fun groupsExpensesByCategory_ignoringIncome() = runTest {
        val txs = listOf(
            Transaction.Expense(id = 1, accountId = 1, amount = usd("30"), categoryId = 7, date = LocalDate.of(2026, 7, 5)),
            Transaction.Expense(id = 2, accountId = 1, amount = usd("20"), categoryId = 7, date = LocalDate.of(2026, 7, 6)),
            Transaction.Expense(id = 3, accountId = 1, amount = usd("15"), categoryId = 9, date = LocalDate.of(2026, 7, 7)),
            Transaction.Income(id = 4, accountId = 1, amount = usd("500"), categoryId = 1, date = LocalDate.of(2026, 7, 8)),
        )
        val byCategory = usecase(txs).invoke(TrackingPeriod.MONTH, julRef).first().totals().associateBy { it.categoryId }

        assertEquals(usd("50"), byCategory.getValue(7).total) // 30 + 20
        assertEquals(usd("15"), byCategory.getValue(9).total)
        assertNull(byCategory[1]) // income category is not in an expense breakdown
    }

    @Test
    fun inPeriodForeignExpense_withNoRate_yieldsMissingRate() = runTest {
        val txs = listOf(
            Transaction.Expense(id = 1, accountId = 1, amount = eur("30"), categoryId = 7, date = LocalDate.of(2026, 7, 5)),
        )
        val result = usecase(txs).invoke(TrackingPeriod.MONTH, julRef).first()
        assertEquals(CategoryBreakdownResult.MissingRate("EUR"), result)
    }

    @Test
    fun outOfPeriodForeignExpense_withNoRate_stillResolves() = runTest {
        // An unrated foreign expense OUTSIDE the period must not block the breakdown.
        val txs = listOf(
            Transaction.Expense(id = 1, accountId = 1, amount = eur("30"), categoryId = 7, date = LocalDate.of(2026, 6, 5)), // June
        )
        val totals = usecase(txs).invoke(TrackingPeriod.MONTH, julRef).first().totals()
        assertTrue(totals.isEmpty())
    }
}
