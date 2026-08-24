package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.PeriodSummary
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeCurrencyRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeRateRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeSettingsRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeTransactionRepository
import org.aristonis.mywallet.domain.usecase.fake.fakeFx
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class ComputePeriodSummaryTest {

    private val julRef = LocalDate.of(2026, 7, 15)
    private fun usd(a: String) = Money.of(a, "USD")
    private fun eur(a: String) = Money.of(a, "EUR")

    // Base USD; EUR is a known currency but has NO rate (empty FakeRateRepository) so foreign amounts
    // can't be converted — that is the "missing rate" condition under test.
    private fun usecase(transactions: List<Transaction>) = ComputePeriodSummary(
        transactions = FakeTransactionRepository(transactions),
        fx = fakeFx(currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)), base = "USD"),
    )

    /** Unwrap the happy-path result; fails loudly if the report was MissingRate. */
    private fun PeriodSummaryResult.summary(): PeriodSummary =
        (this as PeriodSummaryResult.Resolved).summary

    @Test
    fun sumsIncomeExpenseNet_excludingTransfers() = runTest {
        val txs = listOf(
            Transaction.Income(id = 1, accountId = 1, amount = usd("100"), categoryId = 1, date = LocalDate.of(2026, 7, 10)),
            Transaction.Expense(id = 2, accountId = 1, amount = usd("30"), categoryId = 2, date = LocalDate.of(2026, 7, 20)),
            Transaction.Transfer( // excluded from income/expense
                id = 3, sourceAccountId = 1, destAccountId = 2,
                sourceAmount = usd("50"), destAmount = usd("50"),
                rateUsed = BigDecimal.ONE, date = LocalDate.of(2026, 7, 12),
            ),
        )
        val summary = usecase(txs).invoke(TrackingPeriod.MONTH, julRef).first().summary()
        assertEquals(usd("100"), summary.income)
        assertEquals(usd("30"), summary.expense)
        assertEquals(usd("70"), summary.net)
    }

    @Test
    fun transactionsOutsidePeriod_areExcluded() = runTest {
        val txs = listOf(
            Transaction.Income(id = 1, accountId = 1, amount = usd("100"), categoryId = 1, date = LocalDate.of(2026, 6, 10)), // June
        )
        val summary = usecase(txs).invoke(TrackingPeriod.MONTH, julRef).first().summary()
        assertEquals(Money.zero("USD"), summary.income)
        assertEquals(Money.zero("USD"), summary.net)
    }

    @Test
    fun inPeriodForeignTransaction_withNoRate_yieldsMissingRate() = runTest {
        val txs = listOf(
            Transaction.Expense(id = 1, accountId = 1, amount = eur("30"), categoryId = 2, date = LocalDate.of(2026, 7, 20)),
        )
        val result = usecase(txs).invoke(TrackingPeriod.MONTH, julRef).first()
        assertEquals(PeriodSummaryResult.MissingRate("EUR"), result)
    }

    @Test
    fun inPeriodForeignIncome_withNoRate_yieldsMissingRate() = runTest {
        // The summary sums income too, so an unrated foreign INCOME must also gate the report (guards
        // the asymmetric gate: summary = income∪expense, breakdown = expense-only).
        val txs = listOf(
            Transaction.Income(id = 1, accountId = 1, amount = eur("100"), categoryId = 1, date = LocalDate.of(2026, 7, 10)),
        )
        val result = usecase(txs).invoke(TrackingPeriod.MONTH, julRef).first()
        assertEquals(PeriodSummaryResult.MissingRate("EUR"), result)
    }

    @Test
    fun outOfPeriodForeignTransaction_withNoRate_stillResolves() = runTest {
        // An unrated foreign transaction OUTSIDE the period must not block the report — only currencies
        // that actually appear in the period need a rate.
        val txs = listOf(
            Transaction.Expense(id = 1, accountId = 1, amount = eur("30"), categoryId = 2, date = LocalDate.of(2026, 6, 20)), // June
        )
        val summary = usecase(txs).invoke(TrackingPeriod.MONTH, julRef).first().summary()
        assertEquals(Money.zero("USD"), summary.income)
        assertEquals(Money.zero("USD"), summary.expense)
        assertEquals(Money.zero("USD"), summary.net)
    }
}
