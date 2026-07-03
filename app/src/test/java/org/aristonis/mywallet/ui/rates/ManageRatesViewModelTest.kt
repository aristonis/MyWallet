package org.aristonis.mywallet.ui.rates

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.usecase.SetExchangeRate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class ManageRatesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val usd = Currency("USD", "$", 2)
    private val eur = Currency("EUR", "€", 2)
    private val jpy = Currency("JPY", "¥", 0)

    private fun account(currency: String, archived: Boolean = false) =
        Account(id = 0, name = "a-$currency", typeKey = "cash", currencyCode = currency, openingBalance = Money.zero(currency), archived = archived)

    private class Fixture(
        accounts: List<Account>,
        currencies: List<Currency>,
        rates: List<ExchangeRate> = emptyList(),
        base: String = "USD",
    ) {
        val accountRepo = FakeAccountRepository(accounts)
        val currencyRepo = FakeCurrencyRepository(currencies)
        val rateRepo = FakeRateRepository(rates)
        val settingsRepo = FakeSettingsRepository(Settings(baseCurrencyCode = base))
        val viewModel = ManageRatesViewModel(
            accounts = accountRepo,
            currencies = currencyRepo,
            rates = rateRepo,
            settings = settingsRepo,
            setExchangeRate = SetExchangeRate(currencyRepo, rateRepo),
        )
    }

    @Test
    fun rows_listUsedNonBaseCurrencies_excludingBase() = runTest {
        val f = Fixture(
            accounts = listOf(account("USD"), account("EUR"), account("JPY")),
            currencies = listOf(usd, eur, jpy),
        )
        advanceUntilIdle()

        val codes = f.viewModel.state.value.rows.map { it.currencyCode }
        assertEquals(listOf("EUR", "JPY"), codes) // base USD excluded, sorted
        assertEquals("USD", f.viewModel.state.value.baseCurrencyCode)
    }

    @Test
    fun submit_validInput_persistsRate_andRefreshesCurrentRate_withoutClobberingInput() = runTest {
        val f = Fixture(accounts = listOf(account("USD"), account("EUR")), currencies = listOf(usd, eur))
        advanceUntilIdle()

        f.viewModel.setRateInput("EUR", "1.10")
        f.viewModel.submit("EUR")
        advanceUntilIdle()

        // Persisted through the use-case.
        val stored = f.rateRepo.stored.single()
        assertEquals("EUR", stored.currencyCode)
        assertEquals(0, stored.rateToBase.compareTo(BigDecimal("1.10")))

        // The row reflects the saved rate, and the user's typed input is not wiped by the re-emit.
        val row = f.viewModel.state.value.rows.single { it.currencyCode == "EUR" }
        assertNotNull(row.currentRate)
        assertEquals(0, row.currentRate!!.compareTo(BigDecimal("1.10")))
        assertEquals("1.10", row.input)
        assertNull(row.error)
    }

    @Test
    fun submit_nonPositiveRate_failsLoud_withoutPersisting() = runTest {
        val f = Fixture(accounts = listOf(account("USD"), account("EUR")), currencies = listOf(usd, eur))
        advanceUntilIdle()

        f.viewModel.setRateInput("EUR", "0")
        f.viewModel.submit("EUR")
        advanceUntilIdle()

        assertTrue(f.rateRepo.stored.isEmpty())
        assertEquals("Rate must be greater than 0", f.viewModel.state.value.rows.single { it.currencyCode == "EUR" }.error)
    }

    @Test
    fun submit_unparseableRate_failsLoud_withoutPersisting() = runTest {
        val f = Fixture(accounts = listOf(account("USD"), account("EUR")), currencies = listOf(usd, eur))
        advanceUntilIdle()

        f.viewModel.setRateInput("EUR", "not-a-number")
        f.viewModel.submit("EUR")
        advanceUntilIdle()

        assertTrue(f.rateRepo.stored.isEmpty())
        assertEquals("Enter a valid number", f.viewModel.state.value.rows.single { it.currencyCode == "EUR" }.error)
    }

    @Test
    fun rows_excludeCurrenciesUsedOnlyByArchivedAccounts() = runTest {
        val f = Fixture(
            accounts = listOf(account("USD"), account("EUR", archived = true)),
            currencies = listOf(usd, eur),
        )
        advanceUntilIdle()

        // EUR is used only by an archived account, which ComputeNetWorth ignores — so no rate row for it.
        assertTrue(f.viewModel.state.value.rows.isEmpty())
    }

    @Test
    fun submit_absurdExponent_failsLoud_withoutPersisting() = runTest {
        val f = Fixture(accounts = listOf(account("USD"), account("EUR")), currencies = listOf(usd, eur))
        advanceUntilIdle()

        // A pasted extreme exponent is rejected at the boundary — it must never reach toPlainString (OOM).
        f.viewModel.setRateInput("EUR", "1E2000000000")
        f.viewModel.submit("EUR")
        advanceUntilIdle()

        assertTrue(f.rateRepo.stored.isEmpty())
        assertEquals("Enter a realistic rate", f.viewModel.state.value.rows.single { it.currencyCode == "EUR" }.error)
    }
}

// --- Minimal in-memory ports. ---

private class FakeAccountRepository(initial: List<Account>) : AccountRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Account>> = items
    override suspend fun findById(id: Long): Account? = items.value.firstOrNull { it.id == id }
    override suspend fun upsert(account: Account): Long = account.id
}

private class FakeCurrencyRepository(initial: List<Currency>) : CurrencyRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Currency>> = items
    override suspend fun findByCode(code: String): Currency? = items.value.firstOrNull { it.code == code }
}

private class FakeRateRepository(initial: List<ExchangeRate>) : RateRepository {
    private val items = MutableStateFlow(initial)
    val stored: List<ExchangeRate> get() = items.value
    override fun observeAll(): Flow<List<ExchangeRate>> = items
    override suspend fun findByCode(code: String): ExchangeRate? = items.value.firstOrNull { it.currencyCode == code }
    override suspend fun upsert(rate: ExchangeRate) {
        items.value = items.value.filterNot { it.currencyCode == rate.currencyCode } + rate
    }
}

private class FakeSettingsRepository(initial: Settings) : SettingsRepository {
    private val state = MutableStateFlow<Settings?>(initial)
    override fun observe(): Flow<Settings> = state.filterNotNull()
    override fun observeOrNull(): Flow<Settings?> = state
    override suspend fun get(): Settings = state.value ?: error("settings not initialized")
    override suspend fun save(settings: Settings) { state.value = settings }
}
