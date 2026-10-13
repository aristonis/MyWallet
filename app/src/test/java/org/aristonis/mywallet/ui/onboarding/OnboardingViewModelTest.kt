package org.aristonis.mywallet.ui.onboarding

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.ThemePreference
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.di.LocaleDefaults
import org.aristonis.mywallet.domain.usecase.CreateAccount
import org.aristonis.mywallet.domain.usecase.SetBaseCurrency
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    // viewModelScope dispatches on Dispatchers.Main, which doesn't exist in a plain JVM test.
    // A TestDispatcher stands in and lets us drive coroutines deterministically via advanceUntilIdle().
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val usd = Currency("USD", "$", 2)
    private val eur = Currency("EUR", "€", 2)

    private class Fixture(currencies: List<Currency>, localeCurrency: String? = null) {
        val currencyRepo = FakeCurrencyRepository(currencies)
        val settingsRepo = FakeSettingsRepository()
        val accountRepo = FakeAccountRepository()
        val viewModel = OnboardingViewModel(
            currencies = currencyRepo,
            setBaseCurrency = SetBaseCurrency(currencyRepo, settingsRepo),
            createAccount = CreateAccount(currencyRepo, accountRepo),
            localeDefaults = LocaleDefaults(localeCurrency),
            moneyParser = MoneyParser(Locale.US),
        )
    }

    @Test
    fun init_defaultsToDeviceLocaleCurrency_whenSeeded() = runTest {
        val f = Fixture(listOf(usd, eur), localeCurrency = "EUR")
        advanceUntilIdle()

        assertEquals(listOf(usd, eur), f.viewModel.state.value.currencies)
        assertEquals("EUR", f.viewModel.state.value.selectedCurrencyCode)
    }

    @Test
    fun init_fallsBackToFirstSeeded_whenDeviceCurrencyNotAvailable() = runTest {
        val f = Fixture(listOf(usd, eur), localeCurrency = "JPY") // not in the seeded list
        advanceUntilIdle()

        assertEquals("USD", f.viewModel.state.value.selectedCurrencyCode)
    }

    @Test
    fun submit_validInput_setsBaseCurrencyCreatesAccountAndCompletes() = runTest {
        val f = Fixture(listOf(usd, eur))
        advanceUntilIdle()

        f.viewModel.selectCurrency("EUR")
        f.viewModel.setAccountName("Wallet")
        f.viewModel.selectAccountType("savings") // non-default: proves selectAccountType actually mutates
        f.viewModel.setOpeningBalance("150.50")
        f.viewModel.selectTheme(ThemePreference.DARK)
        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(Settings(baseCurrencyCode = "EUR", theme = ThemePreference.DARK), f.settingsRepo.saved)
        val account = f.accountRepo.upserted.single()
        assertEquals("Wallet", account.name)
        assertEquals("savings", account.typeKey)
        assertEquals("EUR", account.currencyCode)
        assertEquals(Money.of("150.50", "EUR"), account.openingBalance)
        assertTrue(f.viewModel.state.value.completed)
        assertNull(f.viewModel.state.value.error)
    }

    @Test
    fun submit_blankOpeningBalance_defaultsToZero() = runTest {
        val f = Fixture(listOf(usd))
        advanceUntilIdle()

        f.viewModel.setAccountName("Cash")
        f.viewModel.setOpeningBalance("")
        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(Money.zero("USD"), f.accountRepo.upserted.single().openingBalance)
        assertTrue(f.viewModel.state.value.completed)
    }

    @Test
    fun submit_invalidOpeningBalance_setsErrorAndDoesNotComplete() = runTest {
        val f = Fixture(listOf(usd))
        advanceUntilIdle()

        f.viewModel.setAccountName("Cash")
        f.viewModel.setOpeningBalance("not-a-number")
        f.viewModel.submit()
        advanceUntilIdle()

        assertNotNull(f.viewModel.state.value.error)
        assertFalse(f.viewModel.state.value.completed)
        assertTrue(f.accountRepo.upserted.isEmpty())
        assertNull("a failed onboarding must not write the base currency", f.settingsRepo.saved)
    }

    @Test
    fun correctOpeningBalance_clearsErrorPreservesDraftAndAllowsResubmit() = runTest {
        val f = Fixture(listOf(usd))
        advanceUntilIdle()

        f.viewModel.setAccountName("Cash")
        f.viewModel.setOpeningBalance("not-a-number")
        f.viewModel.submit()
        advanceUntilIdle()
        assertNotNull(f.viewModel.state.value.error)

        f.viewModel.setOpeningBalance("25.50")
        val corrected = f.viewModel.state.value
        assertNull(corrected.error)
        assertEquals("Cash", corrected.accountName)
        assertEquals("25.50", corrected.openingBalanceInput)
        assertFalse(corrected.completed)

        f.viewModel.submit()
        advanceUntilIdle()
        assertEquals(Money.of("25.50", "USD"), f.accountRepo.upserted.single().openingBalance)
        assertTrue(f.viewModel.state.value.completed)
        assertNull(f.viewModel.state.value.error)
    }

    @Test
    fun submit_blankName_failsLoudWithError() = runTest {
        val f = Fixture(listOf(usd))
        advanceUntilIdle()

        f.viewModel.setAccountName("   ")
        f.viewModel.submit()
        advanceUntilIdle()

        assertNotNull(f.viewModel.state.value.error)
        assertFalse(f.viewModel.state.value.completed)
        assertTrue(f.accountRepo.upserted.isEmpty())
        assertNull("a failed onboarding must not write the base currency", f.settingsRepo.saved)
    }

    @Test
    fun correctAccountName_clearsErrorPreservesBalanceAndAllowsResubmit() = runTest {
        val f = Fixture(listOf(usd))
        advanceUntilIdle()

        f.viewModel.setAccountName("   ")
        f.viewModel.setOpeningBalance("12.00")
        f.viewModel.submit()
        advanceUntilIdle()
        assertNotNull(f.viewModel.state.value.error)

        f.viewModel.setAccountName("Cash")
        val corrected = f.viewModel.state.value
        assertNull(corrected.error)
        assertEquals("Cash", corrected.accountName)
        assertEquals("12.00", corrected.openingBalanceInput)
        assertFalse(corrected.completed)

        f.viewModel.submit()
        advanceUntilIdle()
        assertEquals("Cash", f.accountRepo.upserted.single().name)
        assertEquals(Money.of("12.00", "USD"), f.accountRepo.upserted.single().openingBalance)
        assertTrue(f.viewModel.state.value.completed)
    }

    @Test
    fun submit_whileSaveIsPending_keepsCapturedDraftAndIgnoresDuplicateSubmit() = runTest {
        val f = Fixture(listOf(usd, eur))
        advanceUntilIdle()
        val saveGate = CompletableDeferred<Unit>()
        f.accountRepo.upsertGate = saveGate
        f.viewModel.setAccountName("Cash")
        f.viewModel.setOpeningBalance("25.00")

        f.viewModel.submit()
        runCurrent()
        assertTrue(f.viewModel.state.value.isSubmitting)
        assertEquals(1, f.accountRepo.upsertCalls)

        f.viewModel.setAccountName("Other")
        f.viewModel.setOpeningBalance("99.00")
        f.viewModel.selectCurrency("EUR")
        f.viewModel.selectAccountType("savings")
        f.viewModel.selectTheme(ThemePreference.DARK)
        f.viewModel.submit()
        runCurrent()

        val pending = f.viewModel.state.value
        assertEquals("Cash", pending.accountName)
        assertEquals("25.00", pending.openingBalanceInput)
        assertEquals("USD", pending.selectedCurrencyCode)
        assertEquals("cash", pending.accountTypeKey)
        assertEquals(ThemePreference.SYSTEM, pending.theme)
        assertEquals(1, f.accountRepo.upsertCalls)

        saveGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(Money.of("25.00", "USD"), f.accountRepo.upserted.single().openingBalance)
        assertEquals(Settings(baseCurrencyCode = "USD", theme = ThemePreference.SYSTEM), f.settingsRepo.saved)
        assertTrue(f.viewModel.state.value.completed)
    }
}

// --- Minimal in-memory ports (the :domain fakes live in :domain's test source set, not visible here). ---

private class FakeCurrencyRepository(initial: List<Currency>) : CurrencyRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Currency>> = items
    override suspend fun findByCode(code: String): Currency? = items.value.firstOrNull { it.code == code }
}

private class FakeSettingsRepository : SettingsRepository {
    private val state = MutableStateFlow<Settings?>(null)
    val saved: Settings? get() = state.value
    override fun observe(): Flow<Settings> = throw UnsupportedOperationException("not needed for onboarding")
    override fun observeOrNull(): Flow<Settings?> = state
    override suspend fun get(): Settings = state.value ?: error("settings not initialized")
    override suspend fun save(settings: Settings) { state.value = settings }
}

private class FakeAccountRepository : AccountRepository {
    private val items = MutableStateFlow<List<Account>>(emptyList())
    private var nextId = 1L
    var upsertGate: CompletableDeferred<Unit>? = null
    var upsertCalls = 0
    val upserted: List<Account> get() = items.value
    override fun observeAll(): Flow<List<Account>> = items
    override suspend fun findById(id: Long): Account? = items.value.firstOrNull { it.id == id }
    override suspend fun upsert(account: Account): Long {
        upsertCalls++
        upsertGate?.await()
        val id = if (account.id == 0L) nextId++ else account.id
        items.value = items.value.filterNot { it.id == id } + account.copy(id = id)
        return id
    }

    override suspend fun delete(id: Long) { items.value = items.value.filterNot { it.id == id } }
}
