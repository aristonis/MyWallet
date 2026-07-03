package org.aristonis.mywallet.domain.usecase

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
import java.time.LocalDate

class RecordTransferTest {

    private val today = LocalDate.of(2026, 7, 2)

    private fun account(id: Long, currency: String) =
        Account(id = id, name = "a$id", typeKey = "cash", currencyCode = currency, openingBalance = Money.zero(currency))

    private fun usecase(
        accounts: List<Account>,
        currencies: List<Currency>,
        rates: List<ExchangeRate> = emptyList(),
        base: String = "USD",
        transactions: FakeTransactionRepository = FakeTransactionRepository(),
    ) = RecordTransfer(
        accounts = FakeAccountRepository(accounts),
        currencies = FakeCurrencyRepository(currencies),
        rates = FakeRateRepository(rates),
        settings = FakeSettingsRepository(Settings(baseCurrencyCode = base)),
        transactions = transactions,
    )

    @Test(expected = WalletException.AccountArchived::class)
    fun archivedSource_throws() = runTest {
        usecase(
            accounts = listOf(account(1, "USD").copy(archived = true), account(2, "USD")),
            currencies = listOf(Currency("USD", "$", 2)),
        ).invoke(sourceAccountId = 1, destAccountId = 2, amount = Money.of("10", "USD"), date = today)
    }

    @Test(expected = WalletException.AccountArchived::class)
    fun archivedDestination_throws() = runTest {
        usecase(
            accounts = listOf(account(1, "USD"), account(2, "USD").copy(archived = true)),
            currencies = listOf(Currency("USD", "$", 2)),
        ).invoke(sourceAccountId = 1, destAccountId = 2, amount = Money.of("10", "USD"), date = today)
    }

    @Test
    fun sameCurrency_copiesAmountOneToOne() = runTest {
        val tx = FakeTransactionRepository()
        val transfer = usecase(
            accounts = listOf(account(1, "USD"), account(2, "USD")),
            currencies = listOf(Currency("USD", "$", 2)),
            transactions = tx,
        )

        transfer(sourceAccountId = 1, destAccountId = 2, amount = Money.of("40", "USD"), date = today)

        val saved = tx.added.single() as Transaction.Transfer
        assertEquals(Money.of("40", "USD"), saved.sourceAmount)
        assertEquals(Money.of("40", "USD"), saved.destAmount)
        assertEquals(BigDecimal.ONE, saved.rateUsed)
    }

    @Test
    fun crossCurrency_convertsAndStoresBothAmounts() = runTest {
        val tx = FakeTransactionRepository()
        // base USD; EUR rate 1.10. Transfer 11 USD -> EUR account = 10.00 EUR.
        val transfer = usecase(
            accounts = listOf(account(1, "USD"), account(2, "EUR")),
            currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)),
            rates = listOf(ExchangeRate("EUR", BigDecimal("1.10"))),
            transactions = tx,
        )

        transfer(sourceAccountId = 1, destAccountId = 2, amount = Money.of("11", "USD"), date = today)

        val saved = tx.added.single() as Transaction.Transfer
        assertEquals(Money.of("11", "USD"), saved.sourceAmount)
        assertEquals(Money.of("10.00", "EUR"), saved.destAmount)
    }

    @Test(expected = WalletException.AccountNotFound::class)
    fun unknownSource_throws() = runTest {
        usecase(accounts = listOf(account(2, "USD")), currencies = listOf(Currency("USD", "$", 2)))
            .invoke(sourceAccountId = 1, destAccountId = 2, amount = Money.of("1", "USD"), date = today)
    }

    @Test(expected = WalletException.CurrencyMismatch::class)
    fun amountInWrongCurrency_throws() = runTest {
        usecase(
            accounts = listOf(account(1, "USD"), account(2, "USD")),
            currencies = listOf(Currency("USD", "$", 2)),
        ).invoke(sourceAccountId = 1, destAccountId = 2, amount = Money.of("1", "EUR"), date = today)
    }

    @Test(expected = WalletException.MissingRate::class)
    fun crossCurrency_missingRate_failsLoud() = runTest {
        // dest EUR account but no EUR rate configured
        usecase(
            accounts = listOf(account(1, "USD"), account(2, "EUR")),
            currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)),
            rates = emptyList(),
        ).invoke(sourceAccountId = 1, destAccountId = 2, amount = Money.of("5", "USD"), date = today)
    }
}
