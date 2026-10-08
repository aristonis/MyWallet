package org.aristonis.mywallet.ui.transaction

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * The history opens on everything, exactly as before, and narrows to any period or range from the
 * same date bar Tracking uses. An empty range says so instead of looking like a fresh install.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsListWindowTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private var now = LocalDate.of(2026, 8, 26)
    private val cash = Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.zero("USD"))
    private val food = Category(id = 20, name = "Food", kind = CategoryKind.EXPENSE)

    private fun expenseOn(id: Long, date: LocalDate) = Transaction.Expense(
        id = id, accountId = 1, amount = Money.of("5", "USD"), categoryId = 20, date = date,
    )

    private val history = listOf(
        expenseOn(1, LocalDate.of(2026, 6, 3)),
        expenseOn(2, LocalDate.of(2026, 8, 2)),
        expenseOn(3, LocalDate.of(2026, 8, 26)),
    )

    private fun viewModel(
        transactions: List<Transaction> = history,
        saved: SavedStateHandle = SavedStateHandle(),
        repo: FakeTransactionRepository = FakeTransactionRepository(transactions),
    ) =
        TransactionsListViewModel(
            transactions = repo,
            accounts = FakeAccountRepository(listOf(cash)),
            categories = FakeCategoryRepository(listOf(food)),
            currencies = FakeCurrencyRepository(listOf(Currency("USD", "$", 2))),
            moneyFormatter = MoneyFormatter(Locale.US),
            today = { now },
            savedStateHandle = saved,
        )

    @Test
    fun opensOnEverything() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(TrackingWindow.Period(TrackingPeriod.ALL_TIME, now), vm.state.value.window)
        assertTrue(vm.state.value.showsEverything)
        assertEquals(listOf(3L, 2L, 1L), vm.state.value.rows.map { it.id })
    }

    @Test
    fun narrowingToAMonthListsOnlyThatMonth() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.windowActions.onSelectPeriod(TrackingPeriod.MONTH)
        advanceUntilIdle()

        assertEquals(listOf(3L, 2L), vm.state.value.rows.map { it.id })
        assertFalse(vm.state.value.showsEverything)
    }

    @Test
    fun emptyRangeIsNotFirstRun() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.windowActions.onSelectRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31))
        advanceUntilIdle()

        assertTrue(vm.state.value.sections.isEmpty())
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.showsEverything) // "nothing in this range", not "no transactions yet"
    }

    @Test
    fun anEmptyHistoryIsFirstRun() = runTest {
        val vm = viewModel(transactions = emptyList())
        advanceUntilIdle()

        assertTrue(vm.state.value.sections.isEmpty())
        assertTrue(vm.state.value.showsEverything)
    }

    @Test
    fun clearingARangeShowsEverythingAgain() = runTest {
        val vm = viewModel()
        vm.windowActions.onSelectRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31))
        advanceUntilIdle()

        vm.windowActions.onClearRange()
        advanceUntilIdle()

        assertTrue(vm.state.value.showsEverything)
        assertEquals(3, vm.state.value.rows.size)
    }

    @Test
    fun windowSurvivesRecreation() = runTest {
        val saved = SavedStateHandle()
        val first = viewModel(saved = saved)
        first.windowActions.onSelectPeriod(TrackingPeriod.MONTH)
        first.windowActions.onStep(-2)
        advanceUntilIdle()

        val restored = viewModel(saved = saved)
        advanceUntilIdle()

        assertEquals(first.state.value.window, restored.state.value.window)
        assertEquals(listOf(1L), restored.state.value.rows.map { it.id })
    }

    @Test
    fun todayBecomesYesterdayWhenTheScreenComesBackNextDay() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(DayRelation.TODAY, vm.state.value.sections.first().relation)

        now = LocalDate.of(2026, 8, 27)
        vm.onScreenStart()
        advanceUntilIdle()

        assertEquals(DayRelation.YESTERDAY, vm.state.value.sections.first().relation)
    }

    @Test
    fun theCurrentMonthMovesOnWhenTheScreenComesBackNextMonth() = runTest {
        val vm = viewModel()
        vm.windowActions.onSelectPeriod(TrackingPeriod.MONTH)
        advanceUntilIdle()

        now = LocalDate.of(2026, 9, 2)
        vm.onScreenStart()
        advanceUntilIdle()

        assertEquals(TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 9, 2)), vm.state.value.window)
        assertTrue(vm.state.value.sections.isEmpty()) // nothing recorded in September yet
    }

    @Test
    fun anEntryAddedInsideANarrowedWindowAppears() = runTest {
        val repo = FakeTransactionRepository(history)
        val vm = viewModel(repo = repo)
        vm.windowActions.onSelectPeriod(TrackingPeriod.MONTH)
        advanceUntilIdle()

        repo.add(expenseOn(4, LocalDate.of(2026, 8, 20)))
        advanceUntilIdle()

        assertEquals(listOf(3L, 4L, 2L), vm.state.value.rows.map { it.id })
    }

    @Test
    fun anEntryEditedOutOfANarrowedWindowLeavesTheList() = runTest {
        val repo = FakeTransactionRepository(history)
        val vm = viewModel(repo = repo)
        vm.windowActions.onSelectPeriod(TrackingPeriod.MONTH)
        advanceUntilIdle()

        repo.update(expenseOn(2, LocalDate.of(2026, 7, 2)))
        advanceUntilIdle()

        assertEquals(listOf(3L), vm.state.value.rows.map { it.id })
    }

    @Test
    fun aQuickDoubleStepLandsOnTheLastWindow() = runTest {
        val vm = viewModel()
        vm.windowActions.onSelectPeriod(TrackingPeriod.MONTH)
        vm.windowActions.onStep(-1)
        vm.windowActions.onStep(-1)
        advanceUntilIdle()

        assertEquals(TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 6, 26)), vm.state.value.window)
        assertEquals(listOf(1L), vm.state.value.rows.map { it.id })
    }

    @Test
    fun aCustomRangeSurvivesRecreation() = runTest {
        val saved = SavedStateHandle()
        viewModel(saved = saved).windowActions.onSelectRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10))
        advanceUntilIdle()

        val restored = viewModel(saved = saved)
        advanceUntilIdle()

        assertFalse(restored.state.value.showsEverything)
        assertEquals(listOf(2L), restored.state.value.rows.map { it.id })
    }
}
