package org.aristonis.mywallet.ui.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.RecordingErrorReporter
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.usecase.ComputeNetWorth
import org.aristonis.mywallet.domain.usecase.GetAccountBalances
import org.aristonis.mywallet.domain.usecase.GetAccountBalancesInBase
import org.aristonis.mywallet.ui.HealthLedger
import org.aristonis.mywallet.ui.transaction.FakeAccountRepository
import org.aristonis.mywallet.ui.transaction.FakeCurrencyRepository
import org.aristonis.mywallet.ui.transaction.FakeFxRepository
import org.aristonis.mywallet.ui.transaction.FakeRateRepository
import org.aristonis.mywallet.ui.transaction.FakeSettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Home sums balances from leg projections, which never open a whole entry, so an entry of a kind the
 * app cannot read would be left out of the numbers without a word. Home therefore also reads the
 * ledger's health and shows the cannot-be-read state instead of totals that miss a row.
 *
 * Assumed constructor change: `HomeViewModel(..., observeLedgerHealth: ObserveLedgerHealth, ...)`,
 * a new parameter taking the `ObserveLedgerHealth` use case (called by name here).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeLedgerHealthTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val usd = Currency("USD", "$", 2)
    private val ledger = HealthLedger()
    private val errors = RecordingErrorReporter()

    private fun homeViewModel(): HomeViewModel {
        val accounts = FakeAccountRepository(
            listOf(Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.of("100", "USD"))),
        )
        val currencies = FakeCurrencyRepository(listOf(usd))
        val settings = FakeSettingsRepository(Settings(baseCurrencyCode = "USD"))
        val fx = FakeFxRepository(currencies, FakeRateRepository(), settings)
        val balances = GetAccountBalances(accounts, ledger)
        return HomeViewModel(
            getAccountBalancesInBase = GetAccountBalancesInBase(balances, fx),
            computeNetWorth = ComputeNetWorth(balances, fx),
            observeLedgerHealth = ledger.health(),
            currencies = currencies,
            settings = settings,
            moneyFormatter = MoneyFormatter(Locale.US),
            defaultDispatcher = dispatcher,
            errors = errors,
        )
    }

    @Test
    fun `an unknown kind in ledger health shows the cannot-be-read state with Retry`() = runTest {
        ledger.plantUnknownKind(id = 7, kind = "FROM_THE_FUTURE")
        val vm = homeViewModel()
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.loadFailed)
        assertFalse(state.isLoading)
        assertTrue("no totals are shown over a row that cannot be read", state.accounts.isEmpty())
        val failure = errors.reported.single() as WalletException.UnreadableRow
        assertEquals(7L, failure.id)
    }

    @Test
    fun `Retry reads the ledger health again and shows the wallet once the row is readable`() = runTest {
        ledger.plantUnknownKind()
        val vm = homeViewModel()
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertTrue(vm.state.value.loadFailed)
        val readsBefore = ledger.healthReads

        ledger.heal()
        vm.retry()
        advanceUntilIdle()

        assertTrue("retry must read the health again", ledger.healthReads > readsBefore)
        val state = vm.state.value
        assertFalse(state.loadFailed)
        assertFalse(state.isLoading)
        assertEquals(1, state.accounts.size)
        assertEquals(NetWorthState.Amount("100.00 USD"), state.netWorth)
    }

    @Test
    fun `a healthy ledger shows the wallet`() = runTest {
        val vm = homeViewModel()
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        assertFalse(vm.state.value.loadFailed)
        assertEquals(NetWorthState.Amount("100.00 USD"), vm.state.value.netWorth)
        assertTrue(errors.reported.isEmpty())
    }

    @Test
    fun `a row of an unknown kind that appears while Home is open turns it into the cannot-be-read state`() = runTest {
        val vm = homeViewModel()
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertFalse(vm.state.value.loadFailed)

        ledger.plantUnknownKind(id = 9)
        advanceUntilIdle()

        assertTrue(vm.state.value.loadFailed)
    }
}
