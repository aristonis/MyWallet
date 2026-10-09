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
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.HealthLedger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * The Transactions list opens on one page of the newest entries and loads the next page when the
 * user scrolls to the end. A page is a keyset read: it starts strictly after the last entry already
 * shown, in the list's order (date desc, then id desc), so a page boundary can fall between entries
 * of the same date without skipping or repeating one.
 *
 * Assumed API, which the implementation must provide:
 * - `TransactionsListViewModel(entries: EntryRepository, accounts, categories, currencies,
 *   observeLedgerHealth: ObserveLedgerHealth, moneyFormatter, today, savedStateHandle,
 *   defaultDispatcher, errors)`: `entries` replaces `transactions: TransactionRepository`;
 * - `TransactionsListViewModel.loadMore()`, which the screen calls when its last row comes into view;
 *   it does nothing while a page is loading or when there is no more;
 * - `TransactionsUiState.hasMore: Boolean` (more entries exist behind the rows shown) and
 *   `TransactionsUiState.isLoadingMore: Boolean` (a next page is being read);
 * - `EntryRepository.observePage(range, after: EntryKey?, limit)` as described in the port tests;
 *   the page size is the view model's choice, about 100 entries.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsListPagingTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val newest = LocalDate.of(2026, 8, 20)
    private val cash = Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.zero("USD"))
    private val food = Category(id = 20, name = "Food", kind = CategoryKind.EXPENSE)

    private fun expense(id: Long, date: LocalDate, amount: String = "5.00") = Transaction.Expense(
        id = id, accountId = 1, amount = Money.of(amount, "USD"), categoryId = 20, date = date,
    )

    /** Entry n is dated n days after a fixed day, so a higher id is also a newer date. */
    private fun distinctDays(count: Int): List<Transaction> =
        (1..count).map { expense(it.toLong(), LocalDate.of(2025, 1, 1).plusDays(it.toLong())) }

    private fun expectedOrder(all: List<Transaction>): List<Long> =
        all.sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.id }).map { it.id }

    private fun TestScope.listOver(entries: FakeEntryRepository): TransactionsListViewModel {
        val ledger = HealthLedger()
        return TransactionsListViewModel(
            entries = entries,
            accounts = FakeAccountRepository(listOf(cash)),
            categories = FakeCategoryRepository(listOf(food)),
            currencies = FakeCurrencyRepository(listOf(Currency("USD", "$", 2))),
            observeLedgerHealth = ledger.health(),
            moneyFormatter = MoneyFormatter(Locale.US),
            today = { newest },
            savedStateHandle = SavedStateHandle(),
            defaultDispatcher = dispatcher,
            errors = RecordingErrorReporter(),
        ).also { watch(it) }
    }

    private fun TransactionsListViewModel.shownIds(): List<Long> = state.value.rows.map { it.id }

    /** Scrolls to the end the way the screen does, one page per call, and stops when nothing is left. */
    private fun TestScope.scrollToTheEnd(vm: TransactionsListViewModel) {
        repeat(MAX_PAGES) {
            if (!vm.state.value.hasMore) return
            vm.loadMore()
            advanceUntilIdle()
        }
    }

    @Test
    fun `opening All time reads one page, newest first, not the whole history`() = runTest {
        val entries = FakeEntryRepository(distinctDays(250))

        val vm = listOver(entries)
        advanceUntilIdle()

        val shown = vm.shownIds()
        assertTrue("one page, not all 250: ${shown.size}", shown.size in MIN_PAGE..MAX_PAGE)
        assertEquals((250L downTo 250L - shown.size + 1).toList(), shown)
        assertEquals("the whole history is never read at once", 0, entries.wholeHistoryReads)
        val firstRead = entries.pageReads.single()
        assertEquals(DateRange.ALL_TIME, firstRead.range)
        assertNull(firstRead.after)
        assertTrue(firstRead.limit in MIN_PAGE..MAX_PAGE + 1)
        assertTrue(vm.state.value.hasMore)
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.isLoadingMore)
    }

    @Test
    fun `scrolling to the end loads the next page in order with no gap and no repeat`() = runTest {
        val all = distinctDays(250)
        val vm = listOver(FakeEntryRepository(all))
        advanceUntilIdle()
        val firstPage = vm.shownIds()

        vm.loadMore()
        advanceUntilIdle()

        val afterOne = vm.shownIds()
        assertTrue("the next page adds rows", afterOne.size > firstPage.size)
        assertEquals("the first page is kept as it was", firstPage, afterOne.take(firstPage.size))
        assertEquals(expectedOrder(all).take(afterOne.size), afterOne)

        scrollToTheEnd(vm)

        assertEquals(expectedOrder(all), vm.shownIds())
    }

    @Test
    fun `entries sharing a date across a page boundary are neither skipped nor repeated`() = runTest {
        val sameDay = (1L..230L).map { expense(it, newest) }
        val older = (231L..240L).map { expense(it, newest.minusDays(1)) } + (241L..250L).map { expense(it, newest.minusDays(2)) }
        val all = sameDay + older
        val vm = listOver(FakeEntryRepository(all))
        advanceUntilIdle()

        scrollToTheEnd(vm)

        val shown = vm.shownIds()
        assertEquals("no entry repeats", shown.size, shown.toSet().size)
        assertEquals(expectedOrder(all), shown)
    }

    @Test
    fun `the last page ends loading and a further scroll reads nothing`() = runTest {
        val entries = FakeEntryRepository(distinctDays(150))
        val vm = listOver(entries)
        advanceUntilIdle()

        scrollToTheEnd(vm)

        assertEquals(150, vm.state.value.rows.size)
        assertFalse(vm.state.value.hasMore)
        assertFalse(vm.state.value.isLoadingMore)
        val readsAtTheEnd = entries.pageReads.size

        vm.loadMore()
        advanceUntilIdle()

        assertEquals(readsAtTheEnd, entries.pageReads.size)
        assertEquals(150, vm.state.value.rows.size)
    }

    @Test
    fun `a history that fits one page has no next page`() = runTest {
        val entries = FakeEntryRepository(distinctDays(30))
        val vm = listOver(entries)
        advanceUntilIdle()

        assertEquals(30, vm.state.value.rows.size)
        assertFalse(vm.state.value.hasMore)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(1, entries.pageReads.size)
    }

    @Test
    fun `a new entry refreshes the loaded pages with no gap and no repeat`() = runTest {
        val all = distinctDays(250)
        val entries = FakeEntryRepository(all)
        val vm = listOver(entries)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        val loadedBefore = vm.shownIds().size

        val newId = entries.insert(expense(0, newest))
        advanceUntilIdle()

        val shown = vm.shownIds()
        val everything = all + expense(newId, newest)
        assertEquals("the new entry leads the list", newId, shown.first())
        assertEquals("no entry repeats", shown.size, shown.toSet().size)
        assertEquals("the loaded rows are an unbroken run from the newest", expectedOrder(everything).take(shown.size), shown)
        assertTrue("the pages already loaded stay loaded", shown.size >= loadedBefore)
    }

    @Test
    fun `a deleted entry refreshes the loaded pages with no gap and no repeat`() = runTest {
        val all = distinctDays(250)
        val entries = FakeEntryRepository(all)
        val vm = listOver(entries)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        val loadedBefore = vm.shownIds().size
        val deleted = vm.shownIds()[10]

        entries.delete(deleted)
        advanceUntilIdle()

        val shown = vm.shownIds()
        val remaining = all.filterNot { it.id == deleted }
        assertFalse(deleted in shown)
        assertEquals("no entry repeats", shown.size, shown.toSet().size)
        assertEquals(expectedOrder(remaining).take(shown.size), shown)
        assertTrue("the pages already loaded stay loaded", shown.size >= loadedBefore - 1)
    }

    @Test
    fun `an edited entry refreshes the loaded pages`() = runTest {
        val all = distinctDays(250)
        val entries = FakeEntryRepository(all)
        val vm = listOver(entries)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        val edited = vm.shownIds()[40]

        entries.replace(expense(edited, newest, amount = "99.00"))
        advanceUntilIdle()

        val shown = vm.shownIds()
        val everything = all.map { if (it.id == edited) expense(edited, newest, "99.00") else it }
        assertEquals("the edited entry moved to the front", edited, shown.first())
        assertEquals("99.00 USD", vm.state.value.rows.first().amountDisplay)
        assertEquals("no entry repeats", shown.size, shown.toSet().size)
        assertEquals(expectedOrder(everything).take(shown.size), shown)
    }

    @Test
    fun `a date window narrower than one page lists exactly its entries as before`() = runTest {
        val all = distinctDays(250)
        val entries = FakeEntryRepository(all)
        val vm = listOver(entries)
        advanceUntilIdle()
        val first = LocalDate.of(2025, 1, 1).plusDays(10)
        val second = LocalDate.of(2025, 1, 1).plusDays(14)

        vm.windowActions.onSelectRange(first, second)
        advanceUntilIdle()

        assertEquals(listOf(14L, 13L, 12L, 11L, 10L), vm.shownIds())
        assertFalse(vm.state.value.hasMore)
        assertFalse(vm.state.value.isLoading)
        assertEquals(5, vm.state.value.sections.size)
    }

    @Test
    fun `going back to All time after a narrow window opens on one page again`() = runTest {
        val vm = listOver(FakeEntryRepository(distinctDays(250)))
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        val first = LocalDate.of(2025, 1, 1).plusDays(10)
        vm.windowActions.onSelectRange(first, first.plusDays(2))
        advanceUntilIdle()

        vm.windowActions.onSelectPeriod(TrackingPeriod.ALL_TIME)
        advanceUntilIdle()

        val shown = vm.shownIds()
        assertTrue(shown.size in MIN_PAGE..MAX_PAGE)
        assertEquals(250L, shown.first())
        assertTrue(vm.state.value.hasMore)
    }

    private companion object {
        const val MIN_PAGE = 50
        const val MAX_PAGE = 200
        const val MAX_PAGES = 30
    }
}
