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
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.EntryKey
import org.aristonis.mywallet.ui.HealthLedger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.Locale

/**
 * Loading more reads only the next page, after the last entry shown, instead of reading every loaded
 * page again, and a request that was dropped is not lost.
 *
 * Assumed behaviour of `TransactionsListViewModel`:
 * - `loadMore()` starts one read of `observePage(window.range, after = <key of the last shown entry>,
 *   limit = 101)` (a page of 100 and one more to know whether entries remain) and appends its rows;
 * - a write re-reads the loaded rows in one read whose limit is the number loaded plus one;
 * - a `loadMore()` dropped because the window is changing is remembered and applied once the state
 *   for the current window is built;
 * - `TransactionsListScreen` keys its load-more effect on `isLoadingMore` as well as the row count.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsListAppendTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val newest = LocalDate.of(2026, 8, 20)
    private val cash = Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.zero("USD"))
    private val food = Category(id = 20, name = "Food", kind = CategoryKind.EXPENSE)

    private fun expense(id: Long, date: LocalDate, amount: String = "5.00") = Transaction.Expense(
        id = id, accountId = 1, amount = Money.of(amount, "USD"), categoryId = 20, date = date,
    )

    private fun distinctDays(count: Int): List<Transaction> =
        (1..count).map { expense(it.toLong(), LocalDate.of(2025, 1, 1).plusDays(it.toLong())) }

    private fun expectedOrder(all: List<Transaction>): List<Long> =
        all.sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.id }).map { it.id }

    private fun TestScope.listOver(entries: FakeEntryRepository): TransactionsListViewModel =
        TransactionsListViewModel(
            entries = entries,
            accounts = FakeAccountRepository(listOf(cash)),
            categories = FakeCategoryRepository(listOf(food)),
            currencies = FakeCurrencyRepository(listOf(Currency("USD", "$", 2))),
            settings = FakeSettingsRepository(org.aristonis.mywallet.domain.model.Settings("USD")),
            observeLedgerHealth = HealthLedger().health(),
            moneyFormatter = MoneyFormatter(Locale.US),
            today = { newest },
            savedStateHandle = SavedStateHandle(),
            defaultDispatcher = dispatcher,
            errors = RecordingErrorReporter(),
        ).also { watch(it) }

    private fun TransactionsListViewModel.shownIds(): List<Long> = state.value.rows.map { it.id }

    private fun TransactionsListViewModel.lastShownKey(): EntryKey =
        state.value.rows.last().let { EntryKey(it.date, it.id) }

    @Test
    fun `loadMore reads only the page after the last shown entry`() = runTest {
        val entries = FakeEntryRepository(distinctDays(350))
        val vm = listOver(entries)
        advanceUntilIdle()
        val lastShown = vm.lastShownKey()
        val readsBefore = entries.pageReads.size

        vm.loadMore()
        advanceUntilIdle()

        val newReads = entries.pageReads.drop(readsBefore)
        assertTrue("the next page was read after the last shown entry: $newReads", newReads.any { it.after == lastShown && it.limit == PAGE_READ_LIMIT })
        assertTrue(
            "no read goes back over the first page again: $newReads",
            newReads.none { it.after == null && it.limit > PAGE_READ_LIMIT },
        )
        assertEquals(expectedOrder(distinctDays(350)).take(vm.shownIds().size), vm.shownIds())
        assertTrue(vm.shownIds().size > PAGE_SIZE)
    }

    @Test
    fun `each further page is one read after the last shown entry`() = runTest {
        val entries = FakeEntryRepository(distinctDays(350))
        val vm = listOver(entries)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        val lastShown = vm.lastShownKey()
        val readsBefore = entries.pageReads.size

        vm.loadMore()
        advanceUntilIdle()

        val newReads = entries.pageReads.drop(readsBefore)
        assertEquals("exactly one read for the third page: $newReads", listOf(EntryKey(lastShown.date, lastShown.id)), newReads.map { it.after })
        assertEquals(PAGE_READ_LIMIT, newReads.single().limit)
        assertEquals(expectedOrder(distinctDays(350)).take(vm.shownIds().size), vm.shownIds())
    }

    @Test
    fun `an entry added after two more pages leaves an unbroken list`() = runTest {
        val all = distinctDays(350)
        val entries = FakeEntryRepository(all)
        val vm = listOver(entries)
        advanceUntilIdle()
        vm.loadMore(); advanceUntilIdle()
        vm.loadMore(); advanceUntilIdle()
        val loaded = vm.shownIds().size

        val newId = entries.insert(expense(0, newest))
        advanceUntilIdle()

        val shown = vm.shownIds()
        assertEquals(newId, shown.first())
        assertEquals("no entry repeats", shown.size, shown.toSet().size)
        assertEquals(expectedOrder(all + expense(newId, newest)).take(shown.size), shown)
        assertTrue("the loaded pages stay loaded", shown.size >= loaded)
    }

    @Test
    fun `an entry deleted after two more pages leaves an unbroken list`() = runTest {
        val all = distinctDays(350)
        val entries = FakeEntryRepository(all)
        val vm = listOver(entries)
        advanceUntilIdle()
        vm.loadMore(); advanceUntilIdle()
        vm.loadMore(); advanceUntilIdle()
        val deleted = vm.shownIds()[150]

        entries.delete(deleted)
        advanceUntilIdle()

        val shown = vm.shownIds()
        assertFalse(deleted in shown)
        assertEquals("no entry repeats", shown.size, shown.toSet().size)
        assertEquals(expectedOrder(all.filterNot { it.id == deleted }).take(shown.size), shown)
    }

    @Test
    fun `an entry edited after two more pages leaves an unbroken list`() = runTest {
        val all = distinctDays(350)
        val entries = FakeEntryRepository(all)
        val vm = listOver(entries)
        advanceUntilIdle()
        vm.loadMore(); advanceUntilIdle()
        vm.loadMore(); advanceUntilIdle()
        val edited = vm.shownIds()[250]

        entries.replace(expense(edited, newest, amount = "99.00"))
        advanceUntilIdle()

        val shown = vm.shownIds()
        val everything = all.map { if (it.id == edited) expense(edited, newest, "99.00") else it }
        assertEquals(edited, shown.first())
        assertEquals("no entry repeats", shown.size, shown.toSet().size)
        assertEquals(expectedOrder(everything).take(shown.size), shown)
    }

    @Test
    fun `a loadMore dropped while the window is changing is applied once the new window is built`() = runTest {
        val vm = listOver(FakeEntryRepository(distinctDays(350)))
        advanceUntilIdle()
        val from = LocalDate.of(2025, 1, 1).plusDays(1)
        val to = LocalDate.of(2025, 1, 1).plusDays(300)

        vm.windowActions.onSelectRange(from, to)
        vm.loadMore() // the state still belongs to the old window, so the guard drops this call
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse("loading must not be left on", state.isLoadingMore)
        assertTrue("the dropped request was retried: ${state.rowCount} rows", state.rowCount > PAGE_SIZE)
        assertEquals(expectedOrder(distinctDays(300)).take(state.rowCount), vm.shownIds())
    }

    @Test
    fun `the screen keys its load-more effect on whether a page is loading`() {
        val screen = File("src/main/java/org/aristonis/mywallet/ui/transaction/TransactionsListScreen.kt").readText()

        assertTrue(
            "the effect restarts only when the row count changes",
            Regex("""LaunchedEffect\([^)]*isLoadingMore[^)]*\)""").containsMatchIn(screen),
        )
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val PAGE_READ_LIMIT = 101
    }
}
