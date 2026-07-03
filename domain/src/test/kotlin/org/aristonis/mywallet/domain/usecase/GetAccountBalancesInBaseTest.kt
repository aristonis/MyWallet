package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.usecase.fake.FakeAccountRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeCurrencyRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeRateRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeSettingsRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeTransactionRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class GetAccountBalancesInBaseTest {

    private fun account(id: Long, currency: String, opening: String) =
        Account(id = id, name = "a$id", typeKey = "cash", currencyCode = currency, openingBalance = Money.of(opening, currency))

    private fun usecase(
        accounts: List<Account>,
        currencies: List<Currency>,
        rates: List<ExchangeRate> = emptyList(),
        base: String = "USD",
    ): GetAccountBalancesInBase {
        val getBalances = GetAccountBalances(FakeAccountRepository(accounts), FakeTransactionRepository())
        return GetAccountBalancesInBase(
            getAccountBalances = getBalances,
            currencies = FakeCurrencyRepository(currencies),
            rates = FakeRateRepository(rates),
            settings = FakeSettingsRepository(Settings(baseCurrencyCode = base)),
        )
    }

    @Test
    fun baseCurrencyAccount_baseEqualsNative() = runTest {
        val result = usecase(
            accounts = listOf(account(1, "USD", "100")),
            currencies = listOf(Currency("USD", "$", 2)),
        )().first()

        val row = result.single()
        assertEquals(Money.of("100", "USD"), row.native)
        assertEquals(Money.of("100", "USD"), row.base)
    }

    @Test
    fun nonBaseAccountWithRate_baseIsConverted() = runTest {
        val result = usecase(
            accounts = listOf(account(1, "EUR", "50")),
            currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)),
            rates = listOf(ExchangeRate("EUR", BigDecimal("1.10"))), // 1 EUR = 1.10 USD
        )().first()

        assertEquals(Money.of("55.00", "USD"), result.single().base)
    }

    @Test
    fun missingRate_baseIsNullForThatAccountOnly() = runTest {
        val result = usecase(
            accounts = listOf(account(1, "EUR", "50"), account(2, "USD", "100")),
            currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)),
            rates = emptyList(), // no EUR rate
        )().first()

        assertEquals(2, result.size) // whole list still resolves
        val eur = result.first { it.account.currencyCode == "EUR" }
        val usd = result.first { it.account.currencyCode == "USD" }
        assertNull(eur.base) // this card shows "needs a rate"
        assertEquals(Money.of("100", "USD"), usd.base)
    }
}
