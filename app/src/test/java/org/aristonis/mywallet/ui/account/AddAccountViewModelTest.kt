package org.aristonis.mywallet.ui.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.di.LocaleDefaults
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.usecase.CreateAccount
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddAccountViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val usd = Currency("USD", "$", 2)
    private val eur = Currency("EUR", "€", 2)

    private class Fixture(currencies: List<Currency>, localeCurrency: String? = null) {
        val currencyRepo = FakeCurrencyRepository(currencies)
        val accountRepo = FakeAccountRepository()
        val viewModel = AddAccountViewModel(
            currencies = currencyRepo,
            createAccount = CreateAccount(currencyRepo, accountRepo),
            localeDefaults = LocaleDefaults(localeCurrency),
        )
    }

    @Test
    fun init_defaultsCurrencyToDeviceLocale() = runTest {
        val f = Fixture(listOf(usd, eur), localeCurrency = "EUR")
        advanceUntilIdle()

        assertEquals("EUR", f.viewModel.state.value.selectedCurrencyCode)
    }

    @Test
    fun submit_validInput_createsAccount() = runTest {
        val f = Fixture(listOf(usd, eur))
        advanceUntilIdle()

        f.viewModel.selectCurrency("EUR")
        f.viewModel.setName("Euro Savings")
        f.viewModel.selectAccountType("savings")
        f.viewModel.setOpeningBalance("500")
        f.viewModel.submit()
        advanceUntilIdle()

        val account = f.accountRepo.upserted.single()
        assertEquals("Euro Savings", account.name)
        assertEquals("savings", account.typeKey)
        assertEquals("EUR", account.currencyCode)
        assertEquals(Money.of("500", "EUR"), account.openingBalance)
        assertTrue(f.viewModel.state.value.created)
    }

    @Test
    fun submit_blankName_failsLoudWithoutCreating() = runTest {
        val f = Fixture(listOf(usd))
        advanceUntilIdle()

        f.viewModel.setName("   ")
        f.viewModel.submit()
        advanceUntilIdle()

        assertNotNull(f.viewModel.state.value.error)
        assertFalse(f.viewModel.state.value.created)
        assertTrue(f.accountRepo.upserted.isEmpty())
    }

    @Test
    fun submit_invalidOpeningBalance_failsLoud() = runTest {
        val f = Fixture(listOf(usd))
        advanceUntilIdle()

        f.viewModel.setName("Cash")
        f.viewModel.setOpeningBalance("not-a-number")
        f.viewModel.submit()
        advanceUntilIdle()

        assertNotNull(f.viewModel.state.value.error)
        assertFalse(f.viewModel.state.value.created)
        assertTrue(f.accountRepo.upserted.isEmpty())
    }
}

// --- Minimal in-memory ports. ---

private class FakeCurrencyRepository(initial: List<Currency>) : CurrencyRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Currency>> = items
    override suspend fun findByCode(code: String): Currency? = items.value.firstOrNull { it.code == code }
}

private class FakeAccountRepository : AccountRepository {
    private val items = MutableStateFlow<List<Account>>(emptyList())
    private var nextId = 1L
    val upserted: List<Account> get() = items.value
    override fun observeAll(): Flow<List<Account>> = items
    override suspend fun findById(id: Long): Account? = items.value.firstOrNull { it.id == id }
    override suspend fun upsert(account: Account): Long {
        val id = if (account.id == 0L) nextId++ else account.id
        items.value = items.value.filterNot { it.id == id } + account.copy(id = id)
        return id
    }
}
