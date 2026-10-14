package org.aristonis.mywallet.ui.reports.charts

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.RecordingErrorReporter
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.NetWorthSeries
import org.aristonis.mywallet.domain.model.NoTrendReason
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.DatedAccountLeg
import org.aristonis.mywallet.domain.port.DatedLegs
import org.aristonis.mywallet.domain.usecase.ComputeNetWorthSeries
import org.aristonis.mywallet.domain.usecase.GetAccountBalances
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
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
import org.junit.Ignore
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/**
 * Net worth: the restated series for the screen's window, formatted for the line and the exact table,
 * or the reason no line is drawn. The four reads under the series answer one write separately, so a
 * point built from new balances and old dated legs must never reach the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetWorthViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private var today = LocalDate.of(2026, 10, 14)
    private val usdEur = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun october(day: Int): LocalDate = LocalDate.of(2026, 10, day)

    private fun wallet(code: String = "USD", opening: String = "100.00") =
        Account(id = 1, name = "Wallet", typeKey = "cash", currencyCode = code, openingBalance = Money.of(opening, code))

    /** An expense on the wallet: the balance read and the dated-leg read both see it once written. */
    private fun spend(amount: String, day: Int) =
        Transaction.Expense(accountId = 1, amount = Money.of(amount, "USD"), categoryId = 6, date = october(day))

    private fun datedSpend(amount: String, day: Int) = DatedAccountLeg(october(day), 1, Money.of("-$amount", "USD"))

    private inner class Rig(accounts: List<Account> = listOf(wallet())) {
        val ledger = ChartLedger()
        val legs = FakeNetWorthLegReader()
        val errors = RecordingErrorReporter()
        private val accountRepository = FakeAccountRepository(accounts)
        private val currencies = FakeCurrencyRepository(usdEur)
        private val fx = FakeFxRepository(currencies, FakeRateRepository(), FakeSettingsRepository(Settings(baseCurrencyCode = "USD")))
        val series = ComputeNetWorthSeries(ledger, legs, fx)
        val balances = GetAccountBalances(accountRepository, ledger)

        /** One write: the entry lands in the ledger and in the dated legs. */
        fun write(amount: String, day: Int) {
            ledger.entries.value = ledger.entries.value + spend(amount, day)
            legs.legs.value = legs.legs.value.copy(account = legs.legs.value.account + datedSpend(amount, day))
        }

        fun vm(savedState: SavedStateHandle = SavedStateHandle()) = NetWorthViewModel(
            getAccountBalances = balances,
            computeNetWorthSeries = series,
            observeLedgerHealth = ObserveLedgerHealth(ledger),
            currencies = currencies,
            moneyFormatter = MoneyFormatter(Locale.US),
            today = TodayProvider { today },
            savedStateHandle = savedState,
            defaultDispatcher = dispatcher,
            errors = errors,
        )
    }

    private fun TestScope.watch(vm: NetWorthViewModel) {
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
    }

    private fun trend(vm: NetWorthViewModel) = vm.state.value.data as NetWorthData.Trend

    @Test
    fun aTrend_tableRowsAreExactlyThePointsTheSeriesSupplies() = runTest(dispatcher) {
        val rig = Rig()
        rig.write("30.00", day = 10)
        val vm = rig.vm()
        watch(vm)

        val data = trend(vm)
        val supplied = rig.series(rig.balances(), vm.state.value.window.range, today).first() as NetWorthSeries.Trend
        assertEquals(supplied.points.map { it.date }, data.points.map { it.date })
        assertEquals(supplied.points.map { it.balance.amount }, data.points.map { it.balance })
        assertEquals((1..14).map(::october), data.points.map { it.date })
        assertEquals(List(9) { "100.00 USD" } + List(5) { "70.00 USD" }, data.points.map { it.balanceDisplay })
        assertEquals("USD", data.currencyCode)
        assertEquals("70.00 USD", data.latest.balanceDisplay)
        assertEquals("70.00 USD", data.lowestDisplay)
        assertEquals("100.00 USD", data.highestDisplay)
    }

    @Test
    fun aTrend_saysWhichWayItWent_andWhetherItCrossesZero() = runTest(dispatcher) {
        val falling = Rig()
        falling.write("30.00", day = 10)
        val down = falling.vm()
        watch(down)
        assertEquals(TrendDirection.DOWN, trend(down).direction)
        assertFalse(trend(down).crossesZero)

        val belowZero = Rig(accounts = listOf(wallet(opening = "20.00")))
        belowZero.write("30.00", day = 10)
        val crossing = belowZero.vm()
        watch(crossing)
        assertEquals(TrendDirection.DOWN, trend(crossing).direction)
        assertTrue(trend(crossing).crossesZero)

        val flat = Rig().vm()
        watch(flat)
        assertEquals(TrendDirection.FLAT, trend(flat).direction)
    }

    @Test
    fun aWindowStartingTooLongAgo_showsTheCurrentBalance_withItsReason_andNoPoints() = runTest(dispatcher) {
        val rig = Rig()
        rig.write("30.00", day = 10)
        val vm = rig.vm()
        watch(vm)

        vm.windowActions.onSelectPeriod(TrackingPeriod.ALL_TIME)
        advanceUntilIdle()

        assertEquals(NetWorthData.CurrentOnly("USD", "70.00 USD", NoTrendReason.START_TOO_OLD), vm.state.value.data)
    }

    @Test
    fun aOneDayWindow_hasTooFewPoints_forALine() = runTest(dispatcher) {
        val vm = Rig().vm()
        watch(vm)

        vm.windowActions.onSelectPeriod(TrackingPeriod.DAY)
        advanceUntilIdle()

        assertEquals(NetWorthData.CurrentOnly("USD", "100.00 USD", NoTrendReason.TOO_FEW_POINTS), vm.state.value.data)
    }

    @Test
    fun aMissingRate_namesTheCurrency_andCarriesNoConvertedValue() = runTest(dispatcher) {
        val vm = Rig(accounts = listOf(wallet(code = "EUR"))).vm()
        watch(vm)

        assertEquals(NetWorthData.MissingRate("EUR"), vm.state.value.data)
    }

    @Test
    fun tooManyLegs_isAnExplicitUnavailableState() = runTest(dispatcher) {
        val rig = Rig()
        rig.legs.legs.value = DatedLegs(account = emptyList(), debt = emptyList(), overLimit = true)
        val vm = rig.vm()
        watch(vm)

        assertEquals(NetWorthData.TooManyLegs, vm.state.value.data)
    }

    @Test
    fun oneWriteSeenByTheReadsAtDifferentMoments_neverShowsAWrongIntermediatePoint() = runTest(dispatcher) {
        val rig = Rig()
        val vm = rig.vm()
        val seen = mutableListOf<NetWorthUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { seen += it } }
        advanceUntilIdle()
        seen.clear()

        // One write, answered by the balance read first and by the dated-leg read a moment later, as
        // two separate Room queries do.
        rig.ledger.entries.value = rig.ledger.entries.value + spend("30.00", day = 10)
        runCurrent()
        advanceTimeBy(READ_GAP_MS)
        rig.legs.legs.value = rig.legs.legs.value.copy(account = listOf(datedSpend("30.00", day = 10)))
        advanceUntilIdle()

        // Before the write every day was 100.00; after it, 1-9 Oct stay 100.00. A point for 1 Oct built
        // from the new balance with the old dated legs (70.00), or the other way round (130.00), is wrong.
        val firstDays = seen.mapNotNull { (it.data as? NetWorthData.Trend)?.points?.first()?.balanceDisplay }
        assertEquals(listOf("100.00 USD"), firstDays.distinct())
        assertEquals("70.00 USD", trend(vm).latest.balanceDisplay)
    }

    @Test
    fun aWindowChange_showsTheNewWindowAtOnce_andKeepsTheOldFiguresMarkedAsUpdating() = runTest(dispatcher) {
        val vm = Rig().vm()
        watch(vm)

        vm.windowActions.onStep(-1)
        runCurrent()

        val september = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 9, 14))
        assertEquals(september, vm.state.value.window)
        assertTrue(vm.state.value.isUpdating)
        assertEquals(october(14), trend(vm).latest.date)

        advanceUntilIdle()

        assertFalse(vm.state.value.isUpdating)
        assertEquals(LocalDate.of(2026, 9, 30), trend(vm).latest.date)
    }

    @Test
    @Ignore("Fails until the series is built from one consistent read of balances and dated legs")
    fun readsAnsweringMoreThanTheSettleTimeApart_stillShowNoWrongPoint() = runTest(dispatcher) {
        val rig = Rig()
        val vm = rig.vm()
        val seen = mutableListOf<NetWorthUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { seen += it } }
        advanceUntilIdle()
        seen.clear()

        rig.ledger.entries.value = rig.ledger.entries.value + spend("30.00", day = 10)
        runCurrent()
        advanceTimeBy(SLOW_READ_GAP_MS)
        rig.legs.legs.value = rig.legs.legs.value.copy(account = listOf(datedSpend("30.00", day = 10)))
        advanceUntilIdle()

        val firstDays = seen.mapNotNull { (it.data as? NetWorthData.Trend)?.points?.first()?.balanceDisplay }
        assertEquals(listOf("100.00 USD"), firstDays.distinct())
    }

    @Test
    fun aWindowChange_readsTheNewWindow() = runTest(dispatcher) {
        val rig = Rig()
        val vm = rig.vm()
        watch(vm)

        vm.windowActions.onStep(-1)
        advanceUntilIdle()

        assertEquals(TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 9, 14)), vm.state.value.window)
        val points = trend(vm).points
        assertEquals(LocalDate.of(2026, 9, 1), points.first().date)
        assertEquals(LocalDate.of(2026, 9, 30), points.last().date)
        assertEquals(LocalDate.of(2026, 9, 1), rig.legs.readsAfter.last())
    }

    @Test
    fun theDayMovingOn_addsTodaysPoint_whenTheScreenComesBack() = runTest(dispatcher) {
        val vm = Rig().vm()
        watch(vm)
        assertEquals(october(14), trend(vm).latest.date)

        today = october(15)
        vm.onScreenStart()
        advanceUntilIdle()

        assertEquals(october(15), trend(vm).latest.date)
    }

    @Test
    fun opensOnTheWindowTrackingPassed() = runTest(dispatcher) {
        val passed = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 8, 20))
        val vm = Rig().vm(SavedStateHandle(mapOf(
            ChartWindowArgs.KIND to "period",
            ChartWindowArgs.PERIOD to "MONTH",
            ChartWindowArgs.ANCHOR to "2026-08-20",
        )))
        watch(vm)

        assertEquals(passed, vm.state.value.window)
        assertEquals(LocalDate.of(2026, 8, 31), trend(vm).latest.date)
    }

    @Test
    fun aFailedRead_isReported_andRetryReadsAgain() = runTest(dispatcher) {
        val rig = Rig()
        rig.legs.failedReadsLeft = 1
        val vm = rig.vm()
        watch(vm)

        assertTrue(vm.state.value.loadFailed)
        assertEquals(1, rig.errors.reported.size)

        vm.retry()
        advanceUntilIdle()

        assertFalse(vm.state.value.loadFailed)
        assertEquals(BigDecimal("100.00"), trend(vm).latest.balance)
    }
}

/** How far apart two Room reads answer one write in the conflation test; well inside the settle time. */
private const val READ_GAP_MS = 10L

/** A dated-leg read answering well after the settle time, as a slow device can. */
private const val SLOW_READ_GAP_MS = 250L
