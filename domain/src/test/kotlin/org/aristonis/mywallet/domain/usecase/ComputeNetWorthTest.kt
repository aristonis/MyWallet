package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeAccountRepository
import org.aristonis.mywallet.domain.usecase.fake.fakeFx
import org.aristonis.mywallet.domain.usecase.fake.FakeCurrencyRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeRateRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeSettingsRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeTransactionRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class ComputeNetWorthTest {

    private fun acct(id: Long, currency: String, opening: String) =
        Account(id = id, name = "a$id", typeKey = "cash", currencyCode = currency, openingBalance = Money.of(opening, currency))

    private fun netWorth(
        accounts: List<Account>,
        currencies: List<Currency>,
        rates: List<ExchangeRate> = emptyList(),
        base: String = "USD",
        transactions: List<Transaction> = emptyList(),
    ): ComputeNetWorth {
        val getBalances = GetAccountBalances(FakeAccountRepository(accounts), FakeTransactionRepository(transactions))
        return ComputeNetWorth(
            getAccountBalances = getBalances,
            fx = fakeFx(currencies, rates, base),
        )
    }

    @Test
    fun sumsBalancesConvertedToBase() = runTest {
        // 100 USD + (50 EUR * 1.10 = 55.00 USD) = 155.00 USD
        val nw = netWorth(
            accounts = listOf(acct(1, "USD", "100"), acct(2, "EUR", "50")),
            currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)),
            rates = listOf(ExchangeRate("EUR", BigDecimal("1.10"))),
        )
        assertEquals(NetWorth.Amount(Money.of("155.00", "USD")), nw().first())
    }

    @Test
    fun excludesArchivedAccounts() = runTest {
        val archived = acct(2, "USD", "1000").copy(archived = true)
        val nw = netWorth(
            accounts = listOf(acct(1, "USD", "100"), archived),
            currencies = listOf(Currency("USD", "$", 2)),
        )
        assertEquals(NetWorth.Amount(Money.of("100", "USD")), nw().first())
    }

    @Test
    fun missingRate_reportsTheMissingCurrency() = runTest {
        val nw = netWorth(
            accounts = listOf(acct(1, "USD", "100"), acct(2, "EUR", "50")),
            currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)),
            rates = emptyList(), // no EUR rate → a MissingRate result, never a silently wrong total
        )
        assertEquals(NetWorth.MissingRate("EUR"), nw().first())
    }

    @Test
    fun addingTheMissingRateResolvesTheTotal() = runTest {
        // Because a missing rate is a returned value (not a thrown error that would end the flow), once
        // the rate exists the use-case recomputes to a total instead of staying stuck on MissingRate.
        // (That a single LIVE subscription re-emits on the change is pinned by HomeViewModelTest.)
        val currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2))
        val fx = fakeFx(currencies = currencies, rates = emptyList(), base = "USD")
        val getBalances = GetAccountBalances(
            FakeAccountRepository(listOf(acct(1, "USD", "100"), acct(2, "EUR", "50"))),
            FakeTransactionRepository(),
        )
        val nw = ComputeNetWorth(getAccountBalances = getBalances, fx = fx)

        assertEquals(NetWorth.MissingRate("EUR"), nw().first())

        fx.emit(
            FxSnapshot(
                baseCurrencyCode = "USD",
                ratesToBase = mapOf("EUR" to BigDecimal("1.10")),
                currencies = currencies,
            ),
        )

        assertEquals(NetWorth.Amount(Money.of("155.00", "USD")), nw().first())
    }
}
