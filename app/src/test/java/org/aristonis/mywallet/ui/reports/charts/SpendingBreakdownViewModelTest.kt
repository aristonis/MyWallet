package org.aristonis.mywallet.ui.reports.charts

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.RecordingErrorReporter
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.ComputeCategoryBreakdown
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
import org.aristonis.mywallet.ui.CategoryLabel
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
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/**
 * Spending breakdown: the window's expense categories from the same breakdown Tracking reads, with
 * an exact table and a bar chart drawn from one list of values. A refund that outweighs a category's
 * spending stays negative and is never drawn as a share.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpendingBreakdownViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val today = LocalDate.of(2026, 10, 14)
    private val usdEur = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2))
    private val categories = listOf(
        Category(id = 1, name = "Food", kind = CategoryKind.EXPENSE),
        Category(id = 2, name = "Transport", kind = CategoryKind.EXPENSE),
        Category(id = 3, name = "Salary", kind = CategoryKind.INCOME),
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun usd(a: String) = Money.of(a, "USD")

    private fun expense(amount: Money, category: Long, day: Int) =
        Transaction.Expense(accountId = 1, amount = amount, categoryId = category, date = LocalDate.of(2026, 10, day))

    private fun refund(amount: Money, category: Long, day: Int) =
        Transaction.Refund(accountId = 1, amount = amount, categoryId = category, date = LocalDate.of(2026, 10, day))

    private fun income(amount: Money, category: Long, day: Int) =
        Transaction.Income(accountId = 1, amount = amount, categoryId = category, date = LocalDate.of(2026, 10, day))

    private fun buildVm(
        ledger: ChartLedger,
        savedState: SavedStateHandle = SavedStateHandle(),
        errors: RecordingErrorReporter = RecordingErrorReporter(),
    ): SpendingBreakdownViewModel {
        val currencies = FakeCurrencyRepository(usdEur)
        val fx = FakeFxRepository(currencies, FakeRateRepository(), FakeSettingsRepository(Settings(baseCurrencyCode = "USD")))
        return SpendingBreakdownViewModel(
            computeCategoryBreakdown = ComputeCategoryBreakdown(ledger, fx),
            observeLedgerHealth = ObserveLedgerHealth(ledger),
            categories = FakeCategoryRepository(categories),
            currencies = currencies,
            moneyFormatter = MoneyFormatter(Locale.US),
            today = TodayProvider { today },
            savedStateHandle = savedState,
            defaultDispatcher = dispatcher,
            errors = errors,
        )
    }

    private fun TestScope.watch(vm: SpendingBreakdownViewModel) {
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
    }

    private fun ready(vm: SpendingBreakdownViewModel) = vm.state.value.data as SpendingData.Ready

    @Test
    fun tableAndChart_areBuiltFromTheSameExpenseCategoryValues() = runTest(dispatcher) {
        val vm = buildVm(ChartLedger(listOf(
            expense(usd("120.40"), category = 1, day = 3),
            expense(usd("6.00"), category = 1, day = 5),
            expense(usd("71.00"), category = 2, day = 7),
            income(usd("2000"), category = 3, day = 1),
        )))
        watch(vm)

        val data = ready(vm)
        assertEquals("USD", data.currencyCode)
        assertEquals("197.40 USD", data.totalDisplay)
        assertEquals(listOf(CategoryLabel.Named("Food"), CategoryLabel.Named("Transport")), data.rows.map { it.label })
        assertEquals(listOf(BigDecimal("126.40"), BigDecimal("71.00")), data.rows.map { it.amount })
        assertEquals(listOf("126.40 USD", "71.00 USD"), data.rows.map { it.amountDisplay })
        // One bar per table row, in the table's order, carrying the row's exact amount text.
        assertEquals(data.rows.map { it.id }, data.bars.map { it.id })
        assertEquals(data.rows.map { it.amountDisplay }, data.bars.map { it.amountDisplay })
        // Bars are sized by their share of the window's positive spending, and carry it as a whole %.
        assertEquals(126.40f / 197.40f, data.bars[0].length, 0.0001f)
        assertEquals(71.00f / 197.40f, data.bars[1].length, 0.0001f)
        assertEquals(listOf(64, 36), data.bars.map { it.sharePercent })
    }

    @Test
    fun aNegativeNetCategory_isARefundEffect_andGetsNoBar() = runTest(dispatcher) {
        val vm = buildVm(ChartLedger(listOf(
            expense(usd("10.00"), category = 1, day = 3),
            refund(usd("18.00"), category = 1, day = 4),
            expense(usd("40.00"), category = 2, day = 5),
        )))
        watch(vm)

        val data = ready(vm)
        val food = data.rows.single { it.id == 1L }
        assertEquals(BigDecimal("-8.00"), food.amount)
        assertEquals("-8.00 USD", food.amountDisplay)
        assertTrue(food.isRefundEffect)
        assertTrue(data.hasRefundEffect)
        assertEquals(0f, data.bars.single { it.id == 1L }.length)
        assertFalse(data.bars.single { it.id == 1L }.isDrawn)
        assertTrue(data.bars.single { it.id == 2L }.isDrawn)
        // The headline keeps the sign the categories add up to; nothing is clipped to zero.
        assertEquals("32.00 USD", data.totalDisplay)
    }

    @Test
    fun aWindowWithNoSpending_isEmpty_withAZeroTotal() = runTest(dispatcher) {
        val vm = buildVm(ChartLedger(listOf(income(usd("2000"), category = 3, day = 1))))
        watch(vm)

        val data = ready(vm)
        assertTrue(data.isEmpty)
        assertEquals("0.00 USD", data.totalDisplay)
        assertEquals(emptyList<SpendingBar>(), data.bars)
    }

    @Test
    fun aMissingRate_namesTheCurrency_andShowsNoAmounts() = runTest(dispatcher) {
        val vm = buildVm(ChartLedger(listOf(
            expense(usd("10.00"), category = 1, day = 3),
            expense(Money.of("5.00", "EUR"), category = 2, day = 4),
        )))
        watch(vm)

        assertEquals(SpendingData.MissingRate("EUR"), vm.state.value.data)
    }

    @Test
    fun aFailedRead_isReported_andRetryReadsAgain() = runTest(dispatcher) {
        val ledger = ChartLedger(listOf(expense(usd("10.00"), category = 1, day = 3)))
        ledger.failedCategoryReadsLeft = 1
        val errors = RecordingErrorReporter()
        val vm = buildVm(ledger, errors = errors)
        watch(vm)

        assertTrue(vm.state.value.loadFailed)
        assertEquals(1, errors.reported.size)

        vm.retry()
        advanceUntilIdle()

        assertFalse(vm.state.value.loadFailed)
        assertEquals("10.00 USD", ready(vm).totalDisplay)
    }

    @Test
    fun anUnreadableEntry_showsTheFailure_notAPartialBreakdown() = runTest(dispatcher) {
        val ledger = ChartLedger(listOf(expense(usd("10.00"), category = 1, day = 3)))
        ledger.unknownKind.value = org.aristonis.mywallet.domain.port.UnreadableRowRef(9, "FROM_THE_FUTURE")
        val vm = buildVm(ledger)
        watch(vm)

        assertTrue(vm.state.value.loadFailed)
    }

    @Test
    fun aWindowChange_showsTheNewWindowAtOnce_andKeepsTheOldFiguresMarkedAsUpdating() = runTest(dispatcher) {
        val ledger = ChartLedger(listOf(expense(usd("10.00"), category = 1, day = 3)))
        val vm = buildVm(ledger)
        watch(vm)
        ledger.categoryReadsHang = true

        vm.windowActions.onStep(-1)
        advanceUntilIdle()

        assertEquals(TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 9, 14)), vm.state.value.window)
        assertTrue(vm.state.value.isUpdating)
        assertEquals("10.00 USD", ready(vm).totalDisplay)
    }

    @Test
    fun opensOnTheWindowTrackingPassed_andAWindowChangeReadsThatWindow() = runTest(dispatcher) {
        val september = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 9, 10))
        val savedState = SavedStateHandle(mapOf(
            ChartWindowArgs.KIND to "period",
            ChartWindowArgs.PERIOD to "MONTH",
            ChartWindowArgs.ANCHOR to "2026-09-10",
        ))
        val vm = buildVm(
            ChartLedger(listOf(
                Transaction.Expense(accountId = 1, amount = usd("7.00"), categoryId = 2, date = LocalDate.of(2026, 9, 2)),
                expense(usd("10.00"), category = 1, day = 3),
            )),
            savedState = savedState,
        )
        watch(vm)

        assertEquals(september, vm.state.value.window)
        assertEquals("7.00 USD", ready(vm).totalDisplay)

        vm.windowActions.onStep(1)
        advanceUntilIdle()

        assertEquals(TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 10, 10)), vm.state.value.window)
        assertEquals("10.00 USD", ready(vm).totalDisplay)
    }
}
