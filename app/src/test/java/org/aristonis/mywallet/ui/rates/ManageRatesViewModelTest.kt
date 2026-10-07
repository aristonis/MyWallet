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
import org.aristonis.mywallet.data.format.MoneyParser
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
import org.aristonis.mywallet.ui.message.UiMessage
import java.math.BigDecimal
import java.util.Locale

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
            moneyParser = MoneyParser(Locale.US),
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
        assertEquals(UiMessage.RateNotPositive, f.viewModel.state.value.rows.single { it.currencyCode == "EUR" }.error)
    }

    @Test
    fun submit_unparseableRate_failsLoud_withoutPersisting() = runTest {
        val f = Fixture(accounts = listOf(account("USD"), account("EUR")), currencies = listOf(usd, eur))
        advanceUntilIdle()

        f.viewModel.setRateInput("EUR", "not-a-number")
        f.viewModel.submit("EUR")
        advanceUntilIdle()

        assertTrue(f.rateRepo.stored.isEmpty())
        assertEquals(UiMessage.NotANumber, f.viewModel.state.value.rows.single { it.currencyCode == "EUR" }.error)
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
        assertEquals(UiMessage.RateOutOfRange, f.viewModel.state.value.rows.single { it.currencyCode == "EUR" }.error)
    }
    @Test
    fun changingTheBaseCurrencyDiscardsWhateverWasTyped() = runTest {
        // The typed number meant "so many units of the OLD base". After a re-base it means nothing,
        // and the view model survives navigating away, so the field would still be sitting there.
        // One Save would then store an old-base number as a new-base rate: a permanent error on
        // every figure in that currency, with nothing anywhere saying so.
        val f = Fixture(
            accounts = listOf(account("USD"), account("EUR"), account("JPY")),
            currencies = listOf(usd, eur, jpy),
            rates = listOf(ExchangeRate("EUR", BigDecimal("1.10")), ExchangeRate("JPY", BigDecimal("0.0067"))),
        )
        dispatcher.scheduler.advanceUntilIdle()
        f.viewModel.setRateInput("JPY", "0.0067")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("0.0067", f.viewModel.state.value.rows.first { it.currencyCode == "JPY" }.input)
        // The re-base: every rate replaced and the base swapped, exactly as the use-case hands over.
        f.rateRepo.upsert(ExchangeRate("USD", BigDecimal("0.909090909091")))
        f.rateRepo.upsert(ExchangeRate("JPY", BigDecimal("0.006090909091")))
        f.settingsRepo.save(Settings(baseCurrencyCode = "EUR"))
        dispatcher.scheduler.advanceUntilIdle()
        val jpyRow = f.viewModel.state.value.rows.first { it.currencyCode == "JPY" }
        assertEquals("the field must be reseeded from the new rate", "0.006090909091", jpyRow.input)
    }
    @Test
    fun typingSurvivesAnUnrelatedChangeWhileTheBaseStaysPut() = runTest {
        // Discarding on every emission would throw away the user's typing whenever anything else in
        // the wallet changed, so the reset has to key on the base, not on any change at all.
        val f = Fixture(
            accounts = listOf(account("USD"), account("EUR")),
            currencies = listOf(usd, eur),
            rates = listOf(ExchangeRate("EUR", BigDecimal("1.10"))),
        )
        dispatcher.scheduler.advanceUntilIdle()
        f.viewModel.setRateInput("EUR", "1.23")
        dispatcher.scheduler.advanceUntilIdle()
        f.accountRepo.upsert(account("EUR"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("1.23", f.viewModel.state.value.rows.first { it.currencyCode == "EUR" }.input)
    }
}

// --- Minimal in-memory ports. ---
private class FakeAccountRepository(initial: List<Account>) : AccountRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Account>> = items
    override suspend fun findById(id: Long): Account? = items.value.firstOrNull { it.id == id }
    override suspend fun upsert(account: Account): Long = account.id
    override suspend fun delete(id: Long) { items.value = items.value.filterNot { it.id == id } }
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