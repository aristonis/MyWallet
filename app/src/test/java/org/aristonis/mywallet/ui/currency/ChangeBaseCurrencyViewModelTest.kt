package org.aristonis.mywallet.ui.currency

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.port.BaseCurrencyRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.usecase.ChangeBaseCurrency
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class ChangeBaseCurrencyViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val currencies = listOf(
        Currency("USD", "$", 2),
        Currency("EUR", "€", 2),
        Currency("GBP", "£", 2),
    )

    private class Fakes(rates: List<ExchangeRate>, base: String) {
        val currencyRepo = object : CurrencyRepository {
            private val items = MutableStateFlow(
                listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2), Currency("GBP", "£", 2)),
            )
            override fun observeAll(): Flow<List<Currency>> = items
            override suspend fun findByCode(code: String) = items.value.firstOrNull { it.code == code }
        }
        val rateRepo = object : RateRepository {
            private val items = MutableStateFlow(rates)
            override fun observeAll(): Flow<List<ExchangeRate>> = items
            override suspend fun findByCode(code: String) = items.value.firstOrNull { it.currencyCode == code }
            override suspend fun upsert(rate: ExchangeRate) {
                items.value = items.value.filterNot { it.currencyCode == rate.currencyCode } + rate
            }
        }
        val settingsRepo = object : SettingsRepository {
            private val state = MutableStateFlow<Settings?>(Settings(baseCurrencyCode = base))
            override fun observe(): Flow<Settings> = state.filterNotNull()
            override fun observeOrNull(): Flow<Settings?> = state
            override suspend fun get(): Settings = state.value!!
            override suspend fun save(settings: Settings) { state.value = settings }
        }
        val baseRepo = RecordingBaseCurrencyRepository()
    }

    /** Named rather than anonymous so a test can read back what was handed over. */
    private class RecordingBaseCurrencyRepository : BaseCurrencyRepository {
        var applied: Pair<String, List<ExchangeRate>>? = null
            private set

        override suspend fun rebase(newBaseCurrencyCode: String, rates: List<ExchangeRate>) {
            applied = newBaseCurrencyCode to rates
        }
    }

    private fun viewModel(
        rates: List<ExchangeRate> = listOf(ExchangeRate("EUR", BigDecimal("1.10"))),
        base: String = "USD",
    ): Pair<ChangeBaseCurrencyViewModel, Fakes> {
        val f = Fakes(rates, base)
        val vm = ChangeBaseCurrencyViewModel(
            currencies = f.currencyRepo,
            rates = f.rateRepo,
            settings = f.settingsRepo,
            changeBaseCurrency = ChangeBaseCurrency(f.currencyRepo, f.rateRepo, f.settingsRepo, f.baseRepo),
            moneyParser = MoneyParser(Locale.US),
        )
        return vm to f
    }

    @Test
    fun offersEveryCurrencyExceptTheOneAlreadyInUse() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("EUR", "GBP"), vm.state.value.selectable.map { it.code })
        assertEquals("USD", vm.state.value.currentBase)
    }

    @Test
    fun aCurrencyThatAlreadyHasARateNeedsNoTyping() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.select("EUR")

        assertFalse("EUR is already rated, so nothing more is needed", vm.state.value.needsRate)
        assertTrue(vm.state.value.canApply)
    }

    @Test
    fun aCurrencyWithNoRateAsksForOneBeforeItCanBeApplied() = runTest(dispatcher) {
        // Manage Rates only lists currencies an account holds, so a currency you do not own yet
        // could never be given a rate there — and that is the usual reason to change base at all.
        val (vm, _) = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.select("GBP")

        assertTrue(vm.state.value.needsRate)
        assertFalse("nothing to divide by yet", vm.state.value.canApply)

        vm.setRateInput("1.27")
        assertTrue(vm.state.value.canApply)
    }

    @Test
    fun appliesTheChangeThroughTheUseCase() = runTest(dispatcher) {
        val (vm, f) = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.select("EUR")
        vm.apply()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("EUR", f.baseRepo.applied?.first)
        assertTrue(vm.state.value.applied)
    }

    @Test
    fun carriesTheTypedRateIntoTheChange() = runTest(dispatcher) {
        val (vm, f) = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.select("GBP")
        vm.setRateInput("1.27")
        vm.apply()
        dispatcher.scheduler.advanceUntilIdle()

        val applied = f.baseRepo.applied
        assertEquals("GBP", applied?.first)
        // 1.10 / 1.27 — EUR re-expressed against the new base rather than left as it was.
        val eur = applied?.second?.first { it.currencyCode == "EUR" }?.rateToBase
        assertEquals(0, eur!!.compareTo(BigDecimal("0.866141732283464566929133858268")))
    }

    @Test
    fun anUnparseableRateIsReportedAndNothingIsApplied() = runTest(dispatcher) {
        val (vm, f) = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.select("GBP")
        vm.setRateInput("not a number")
        vm.apply()
        dispatcher.scheduler.advanceUntilIdle()

        assertNotNull(vm.state.value.error)
        assertNull(f.baseRepo.applied)
    }

    @Test
    fun changingTheChosenCurrencyClearsAStaleTypedRate() = runTest(dispatcher) {
        // The number was entered for a different currency; carrying it over would apply a rate that
        // was never meant for the one now selected.
        val (vm, _) = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.select("GBP")
        vm.setRateInput("1.27")
        vm.select("EUR")

        assertEquals("", vm.state.value.rateInput)
    }
}
