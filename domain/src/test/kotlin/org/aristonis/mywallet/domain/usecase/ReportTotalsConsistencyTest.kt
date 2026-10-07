package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeTransactionRepository
import org.aristonis.mywallet.domain.usecase.fake.fakeFx
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** The summary card and the category list on one screen must never disagree by a cent. */
class ReportTotalsConsistencyTest {

    private val july = DateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31))
    private val day = LocalDate.of(2026, 7, 10)
    private fun usd(a: String) = Money.of(a, "USD")
    private fun eur(a: String) = Money.of(a, "EUR")

    @Test
    fun summaryEqualsTheSumOfTheBreakdown() = runTest {
        val txs = listOf(
            Transaction.Expense(id = 1, accountId = 1, amount = eur("0.33"), categoryId = 7, subCategoryId = 71, date = day),
            Transaction.Expense(id = 2, accountId = 1, amount = eur("0.33"), categoryId = 7, date = day),
            Transaction.Expense(id = 3, accountId = 1, amount = usd("12.45"), categoryId = 9, date = day),
            Transaction.Income(id = 4, accountId = 1, amount = eur("100.05"), categoryId = 1, date = day),
            Transaction.Income(id = 5, accountId = 1, amount = usd("7"), categoryId = 2, date = day),
        )
        val repo = FakeTransactionRepository(txs)
        val fx = fakeFx(
            currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)),
            rates = listOf(ExchangeRate("EUR", BigDecimal("1.1"))),
            base = "USD",
        )

        val summary = (ComputePeriodSummary(repo, fx).invoke(july).first() as PeriodSummaryResult.Resolved).summary
        val breakdown = (ComputeCategoryBreakdown(repo, fx).invoke(july).first() as CategoryBreakdownResult.Resolved).breakdown

        assertEquals(summary.expense, breakdown.expense.fold(usd("0")) { acc, c -> acc + c.total })
        assertEquals(summary.income, breakdown.income.fold(usd("0")) { acc, c -> acc + c.total })
    }
}
