package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeCurrencyRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeRateRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeSettingsRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeTransactionRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class ComputePeriodSummaryTest {

    private val julRef = LocalDate.of(2026, 7, 15)
    private fun usd(a: String) = Money.of(a, "USD")

    private fun usecase(transactions: List<Transaction>) = ComputePeriodSummary(
        transactions = FakeTransactionRepository(transactions),
        currencies = FakeCurrencyRepository(listOf(Currency("USD", "$", 2))),
        rates = FakeRateRepository(),
        settings = FakeSettingsRepository(Settings(baseCurrencyCode = "USD")),
    )

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
        val summary = usecase(txs).invoke(TrackingPeriod.MONTH, julRef).first()
        assertEquals(usd("100"), summary.income)
        assertEquals(usd("30"), summary.expense)
        assertEquals(usd("70"), summary.net)
    }

    @Test
    fun transactionsOutsidePeriod_areExcluded() = runTest {
        val txs = listOf(
            Transaction.Income(id = 1, accountId = 1, amount = usd("100"), categoryId = 1, date = LocalDate.of(2026, 6, 10)), // June
        )
        val summary = usecase(txs).invoke(TrackingPeriod.MONTH, julRef).first()
        assertEquals(Money.zero("USD"), summary.income)
        assertEquals(Money.zero("USD"), summary.net)
    }
}
