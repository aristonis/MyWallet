package org.aristonis.mywallet.ui.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.port.TransactionRepository
import org.aristonis.mywallet.domain.usecase.ComputeNetWorth
import org.aristonis.mywallet.domain.usecase.GetAccountBalances
import org.aristonis.mywallet.domain.usecase.GetAccountBalancesInBase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale
import org.aristonis.mywallet.ui.transaction.FakeFxRepository

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    // Locale.US pins grouping/decimal separators so "1,000.50 USD" is deterministic on any host.
    private val moneyFormatter = MoneyFormatter(Locale.US)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun account(currency: String, opening: String) =
        Account(id = 1, name = "Acc", typeKey = "cash", currencyCode = currency, openingBalance = Money.of(opening, currency))

    private fun buildVm(
        accounts: List<Account>,
        currencies: List<Currency>,
        rates: List<ExchangeRate> = emptyList(),
        base: String = "USD",
    ): HomeViewModel {
        val accountRepo = FakeAccountRepository(accounts)
        val txRepo = FakeTransactionRepository()
        val currencyRepo = FakeCurrencyRepository(currencies)
        val rateRepo = FakeRateRepository(rates)
        val settingsRepo = FakeSettingsRepository(Settings(baseCurrencyCode = base))
        val getBalances = GetAccountBalances(accountRepo, txRepo)
        val getBalancesInBase = GetAccountBalancesInBase(getBalances, FakeFxRepository(currencyRepo, rateRepo, settingsRepo))
        val computeNetWorth = ComputeNetWorth(getBalances, FakeFxRepository(currencyRepo, rateRepo, settingsRepo))
        return HomeViewModel(getBalancesInBase, computeNetWorth, currencyRepo, settingsRepo, moneyFormatter)
    }

    @Test
    fun singleBaseAccount_netWorthEqualsItsBalance() = runTest {
        val vm = buildVm(
            accounts = listOf(account("USD", "100")),
            currencies = listOf(Currency("USD", "$", 2)),
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("USD", state.baseCurrencyCode)
        assertEquals(1, state.accounts.size)
        assertEquals(Money.of("100", "USD"), state.accounts.first().native)
        assertEquals(Money.of("100", "USD"), state.accounts.first().base)
        assertEquals(NetWorthState.Amount("100.00 USD"), state.netWorth)
    }

    @Test
    fun netWorthAndAccountRows_areFormattedPerCurrencyAndLocale() = runTest {
        // USD 2-decimals with thousands grouping: net worth and the native row both render "1,000.50 USD".
        val vm = buildVm(
            accounts = listOf(account("USD", "1000.5")),
            currencies = listOf(Currency("USD", "$", 2)),
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(NetWorthState.Amount("1,000.50 USD"), state.netWorth)
        assertEquals("1,000.50 USD", state.accounts.first().nativeDisplay)
        assertEquals("1,000.50 USD", state.accounts.first().baseDisplay)
    }

    @Test
    fun jpyAccountRow_formatsWithNoDecimals() = runTest {
        // A JPY account (0 fraction digits) renders "1,000 JPY" from the STORED Currency.decimalPlaces.
        val vm = buildVm(
            accounts = listOf(account("JPY", "1000")),
            currencies = listOf(Currency("USD", "$", 2), Currency("JPY", "¥", 0)),
            rates = listOf(ExchangeRate("JPY", BigDecimal("0.0067"))),
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals("1,000 JPY", vm.state.value.accounts.first().nativeDisplay)
    }

    @Test
    fun archivedAccounts_areHiddenFromTheList() = runTest {
        val active = Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.of("100", "USD"))
        val archived = Account(id = 2, name = "Old", typeKey = "cash", currencyCode = "USD", openingBalance = Money.of("50", "USD"), archived = true)
        val vm = buildVm(
            accounts = listOf(active, archived),
            currencies = listOf(Currency("USD", "$", 2)),
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        // Archived accounts stay out of the active list (they're already out of net worth).
        assertEquals(listOf(1L), vm.state.value.accounts.map { it.account.id })
    }

    @Test
    fun nonBaseAccountWithRate_convertsToBase() = runTest {
        val vm = buildVm(
            accounts = listOf(account("EUR", "50")),
            currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)),
            rates = listOf(ExchangeRate("EUR", BigDecimal("1.10"))), // 1 EUR = 1.10 USD
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(NetWorthState.Amount("55.00 USD"), vm.state.value.netWorth)
    }

    @Test
    fun nonBaseAccountWithoutRate_failsLoudWithMissingRate() = runTest {
        val vm = buildVm(
            accounts = listOf(account("EUR", "50")),
            currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)),
            rates = emptyList(), // no EUR rate
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(NetWorthState.MissingRate("EUR"), vm.state.value.netWorth)
        assertEquals(1, vm.state.value.accounts.size) // accounts still shown
    }

    @Test
    fun netWorthReResolvesLive_whenRateIsAddedWhileSubscribed() = runTest {
        // The regression this guards: net worth must recover from MissingRate to a total once the rate
        // is set, on the SAME live state subscription (the old .catch terminated the flow and never did).
        val accountRepo = FakeAccountRepository(listOf(account("EUR", "50")))
        val currencyRepo = FakeCurrencyRepository(listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)))
        val rateRepo = FakeRateRepository(emptyList())
        val settingsRepo = FakeSettingsRepository(Settings(baseCurrencyCode = "USD"))
        val getBalances = GetAccountBalances(accountRepo, FakeTransactionRepository())
        val vm = HomeViewModel(
            GetAccountBalancesInBase(getBalances, FakeFxRepository(currencyRepo, rateRepo, settingsRepo)),
            ComputeNetWorth(getBalances, FakeFxRepository(currencyRepo, rateRepo, settingsRepo)),
            currencyRepo,
            settingsRepo,
            moneyFormatter,
        )

        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(NetWorthState.MissingRate("EUR"), vm.state.value.netWorth)

        rateRepo.upsert(ExchangeRate("EUR", BigDecimal("1.10")))
        advanceUntilIdle()

        assertEquals(NetWorthState.Amount("55.00 USD"), vm.state.value.netWorth)
    }
}

// --- Minimal in-memory ports (domain fakes live in :domain's test source set). ---

private class FakeAccountRepository(initial: List<Account>) : AccountRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Account>> = items
    override suspend fun findById(id: Long): Account? = items.value.firstOrNull { it.id == id }
    override suspend fun upsert(account: Account): Long = account.id
    override suspend fun delete(id: Long) { items.value = items.value.filterNot { it.id == id } }
}

private class FakeTransactionRepository : TransactionRepository {
    private val items = MutableStateFlow<List<Transaction>>(emptyList())
    override fun observeAll(): Flow<List<Transaction>> = items
    override suspend fun findById(id: Long): Transaction? = items.value.firstOrNull { it.id == id }
    override suspend fun add(transaction: Transaction): Long = 1
    override suspend fun update(transaction: Transaction) { items.value = items.value.map { if (it.id == transaction.id) transaction else it } }
    override suspend fun delete(id: Long) { items.value = items.value.filterNot { it.id == id } }
}

private class FakeCurrencyRepository(initial: List<Currency>) : CurrencyRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Currency>> = items
    override suspend fun findByCode(code: String): Currency? = items.value.firstOrNull { it.code == code }
}

private class FakeRateRepository(initial: List<ExchangeRate>) : RateRepository {
    private val items = MutableStateFlow(initial)
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
