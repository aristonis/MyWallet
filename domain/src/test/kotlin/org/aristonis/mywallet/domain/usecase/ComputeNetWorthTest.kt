package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeAccountRepository
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
            currencies = FakeCurrencyRepository(currencies),
            rates = FakeRateRepository(rates),
            settings = FakeSettingsRepository(Settings(baseCurrencyCode = base)),
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
        assertEquals(Money.of("155.00", "USD"), nw().first())
    }

    @Test
    fun excludesArchivedAccounts() = runTest {
        val archived = acct(2, "USD", "1000").copy(archived = true)
        val nw = netWorth(
            accounts = listOf(acct(1, "USD", "100"), archived),
            currencies = listOf(Currency("USD", "$", 2)),
        )
        assertEquals(Money.of("100", "USD"), nw().first())
    }

    @Test(expected = WalletException.MissingRate::class)
    fun missingRate_failsLoud() = runTest {
        val nw = netWorth(
            accounts = listOf(acct(1, "USD", "100"), acct(2, "EUR", "50")),
            currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)),
            rates = emptyList(), // no EUR rate → must fail loud, not silently wrong
        )
        nw().first()
    }
}
