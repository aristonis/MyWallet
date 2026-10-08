package org.aristonis.mywallet.ui.transaction

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.RecordingErrorReporter
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * An entry saved from this tab whose date the list's dates do not cover would otherwise vanish on
 * return: the user just saved it and cannot see it. The list is told the saved date, says where the
 * entry went, and offers to show it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsListSavedOutsideTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val today = LocalDate.of(2026, 9, 15)
    private val endOfAugust = LocalDate.of(2026, 8, 30)
    private val cash = Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.zero("USD"))
    private val food = Category(id = 20, name = "Food", kind = CategoryKind.EXPENSE)

    private fun TestScope.viewModel() = TransactionsListViewModel(
        transactions = FakeTransactionRepository(),
        accounts = FakeAccountRepository(listOf(cash)),
        categories = FakeCategoryRepository(listOf(food)),
        currencies = FakeCurrencyRepository(listOf(Currency("USD", "$", 2))),
        moneyFormatter = MoneyFormatter(Locale.US),
        today = { today },
        savedStateHandle = SavedStateHandle(),
        defaultDispatcher = dispatcher,
        errors = RecordingErrorReporter(),
    ).also { watch(it) }

    private fun TestScope.onSeptember(): TransactionsListViewModel {
        val vm = viewModel()
        vm.windowActions.onSelectPeriod(TrackingPeriod.MONTH)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun anEntrySavedOutsideTheWindowSaysSo() = runTest {
        val vm = onSeptember()

        vm.onEntrySaved(endOfAugust)
        advanceUntilIdle()

        assertEquals(endOfAugust, vm.state.value.savedOutsideWindow)
    }

    @Test
    fun showMovesTheWindowToTheEntry() = runTest {
        val vm = onSeptember()
        vm.onEntrySaved(endOfAugust)
        advanceUntilIdle()

        vm.showSavedEntry()
        advanceUntilIdle()

        assertEquals(TrackingWindow.Period(TrackingPeriod.MONTH, endOfAugust), vm.state.value.window)
        assertNull(vm.state.value.savedOutsideWindow)
    }

    @Test
    fun showFromACustomRangeLandsOnTheEntrysMonth() = runTest {
        val vm = viewModel()
        vm.windowActions.onSelectRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 10))
        advanceUntilIdle()
        vm.onEntrySaved(endOfAugust)
        advanceUntilIdle()

        vm.showSavedEntry()
        advanceUntilIdle()

        assertEquals(TrackingWindow.Period(TrackingPeriod.MONTH, endOfAugust), vm.state.value.window)
    }

    @Test
    fun anEntryInsideTheWindowSaysNothing() = runTest {
        val vm = onSeptember()

        vm.onEntrySaved(LocalDate.of(2026, 9, 2))
        advanceUntilIdle()

        assertNull(vm.state.value.savedOutsideWindow)
    }

    @Test
    fun allTimeNeverSaysAnything() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onEntrySaved(LocalDate.of(1999, 1, 1))
        advanceUntilIdle()

        assertNull(vm.state.value.savedOutsideWindow)
    }

    @Test
    fun theCurrentWindowDecidesNotTheLastOneShown() = runTest {
        // The user narrowed to September a moment ago and the list has not been rebuilt yet: the
        // shown state still says all time, but the entry is already outside the dates in force.
        val vm = viewModel()
        advanceUntilIdle()
        vm.windowActions.onSelectPeriod(TrackingPeriod.MONTH)
        assertEquals(TrackingPeriod.ALL_TIME, (vm.state.value.window as TrackingWindow.Period).period)

        vm.onEntrySaved(endOfAugust)
        advanceUntilIdle()

        assertEquals(endOfAugust, vm.state.value.savedOutsideWindow)
    }

    @Test
    fun dismissingClearsTheMessageAndTheNextSaveSaysSoAgain() = runTest {
        val vm = onSeptember()
        vm.onEntrySaved(endOfAugust)
        advanceUntilIdle()

        vm.dismissSavedEntryMessage()
        advanceUntilIdle()
        assertNull(vm.state.value.savedOutsideWindow)

        vm.onEntrySaved(endOfAugust)
        advanceUntilIdle()
        assertEquals(endOfAugust, vm.state.value.savedOutsideWindow)
    }

    @Test
    fun aSaveInsideTheWindowReplacesAnUnansweredMessage() = runTest {
        // The user left for the editor while the snackbar was up; the next save is the one to talk about.
        val vm = onSeptember()
        vm.onEntrySaved(endOfAugust)
        advanceUntilIdle()

        vm.onEntrySaved(LocalDate.of(2026, 9, 2))
        advanceUntilIdle()

        assertNull(vm.state.value.savedOutsideWindow)
    }

    @Test
    fun movingTheDatesClearsTheMessage() = runTest {
        // Once the user picks other dates, "outside these dates" no longer describes what is on screen.
        val vm = onSeptember()
        vm.onEntrySaved(endOfAugust)
        advanceUntilIdle()

        vm.windowActions.onStep(-1)
        advanceUntilIdle()

        assertNull(vm.state.value.savedOutsideWindow)
    }
}
