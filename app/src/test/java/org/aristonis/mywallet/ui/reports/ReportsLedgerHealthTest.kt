package org.aristonis.mywallet.ui.reports

import androidx.lifecycle.SavedStateHandle
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
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.usecase.ComputeCategoryBreakdown
import org.aristonis.mywallet.ui.HealthLedger
import org.aristonis.mywallet.ui.transaction.FakeCategoryRepository
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
import java.time.LocalDate
import java.util.Locale

/**
 * Tracking sums category legs, which never open a whole entry, so an entry of a kind the app cannot
 * read would be left out of the totals. Tracking combines the ledger's health with its own read and
 * shows the cannot-be-read state with Retry instead.
 *
 * Assumed constructor change: `ReportsViewModel(..., observeLedgerHealth: ObserveLedgerHealth, ...)`,
 * a new parameter (called by name here).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsLedgerHealthTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val ledger = HealthLedger()
    private val errors = RecordingErrorReporter()

    private fun reportsViewModel(): ReportsViewModel {
        val currencies = FakeCurrencyRepository(listOf(Currency("USD", "$", 2)))
        val settings = FakeSettingsRepository(Settings(baseCurrencyCode = "USD"))
        return ReportsViewModel(
            computeCategoryBreakdown = ComputeCategoryBreakdown(ledger, FakeFxRepository(currencies, FakeRateRepository(), settings)),
            observeLedgerHealth = ledger.health(),
            categories = FakeCategoryRepository(),
            currencies = currencies,
            moneyFormatter = MoneyFormatter(Locale.US),
            today = TodayProvider { LocalDate.of(2026, 7, 15) },
            savedStateHandle = SavedStateHandle(),
            defaultDispatcher = dispatcher,
            errors = errors,
        )
    }

    @Test
    fun `an unknown kind in ledger health shows the cannot-be-read state with Retry`() = runTest {
        ledger.plantUnknownKind(id = 7)
        val vm = reportsViewModel()
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        assertTrue(vm.state.value.loadFailed)
        assertEquals(ReportsData.Loading, vm.state.value.data)
        assertEquals(7L, (errors.reported.single() as WalletException.UnreadableRow).id)
    }

    @Test
    fun `Retry reads the ledger health again and shows the report once the row is readable`() = runTest {
        ledger.plantUnknownKind()
        val vm = reportsViewModel()
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertTrue(vm.state.value.loadFailed)
        val readsBefore = ledger.healthReads

        ledger.heal()
        vm.retry()
        advanceUntilIdle()

        assertTrue("retry must read the health again", ledger.healthReads > readsBefore)
        assertFalse(vm.state.value.loadFailed)
        assertTrue(vm.state.value.data is ReportsData.Ready)
    }

    @Test
    fun `a row that cannot be read is not hidden by choosing other dates`() = runTest {
        ledger.plantUnknownKind()
        val vm = reportsViewModel()
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.windowActions.onStep(1)
        advanceUntilIdle()

        assertTrue(vm.state.value.loadFailed)
    }

    @Test
    fun `a healthy ledger shows the report`() = runTest {
        val vm = reportsViewModel()
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        assertFalse(vm.state.value.loadFailed)
        assertTrue(vm.state.value.data is ReportsData.Ready)
    }
}
