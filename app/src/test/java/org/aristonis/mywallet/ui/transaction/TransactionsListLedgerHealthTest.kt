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
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
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
 * The Transactions list reads whole entries, but an entry of a kind the app cannot read is not on
 * the page it would belong to, so the list also checks the ledger's health and shows the
 * cannot-be-read state with Retry rather than a list with a silent hole.
 *
 * Assumed constructor change: `TransactionsListViewModel(..., observeLedgerHealth: ObserveLedgerHealth, ...)`
 * (see [TransactionsListPagingTest] for the rest of the new shape).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsListLedgerHealthTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val day = LocalDate.of(2026, 8, 20)
    private val cash = Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.zero("USD"))
    private val savings = Account(id = 2, name = "Savings", typeKey = "savings", currencyCode = "USD", openingBalance = Money.zero("USD"))
    private val food = Category(id = 20, name = "Food", kind = CategoryKind.EXPENSE)
    private val ledger = HealthLedger()
    private val errors = RecordingErrorReporter()

    private fun expense(id: Long, date: LocalDate) =
        Transaction.Expense(id = id, accountId = 1, amount = Money.of("5.00", "USD"), categoryId = 20, date = date)

    private fun TestScope.listOver(entries: FakeEntryRepository) = TransactionsListViewModel(
        debts = FakeDebtRepository(),
        entries = entries,
        accounts = FakeAccountRepository(listOf(cash, savings)),
        categories = FakeCategoryRepository(listOf(food)),
        currencies = FakeCurrencyRepository(listOf(Currency("USD", "$", 2))),
        settings = FakeSettingsRepository(org.aristonis.mywallet.domain.model.Settings("USD")),
        observeLedgerHealth = ledger.health(),
        moneyFormatter = MoneyFormatter(Locale.US),
        today = { day },
        savedStateHandle = SavedStateHandle(),
        defaultDispatcher = dispatcher,
        errors = errors,
    ).also { watch(it) }

    @Test
    fun `an unknown kind in ledger health shows the cannot-be-read state with Retry`() = runTest {
        ledger.plantUnknownKind(id = 7)
        val vm = listOver(FakeEntryRepository(listOf(expense(1, day))))
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.loadFailed)
        assertFalse(state.isLoading)
        assertTrue(state.sections.isEmpty())
        assertEquals(7L, (errors.reported.single() as WalletException.UnreadableRow).id)
    }

    @Test
    fun `Retry reads the ledger health again and lists the entries once the row is readable`() = runTest {
        ledger.plantUnknownKind()
        val vm = listOver(FakeEntryRepository(listOf(expense(1, day))))
        advanceUntilIdle()
        assertTrue(vm.state.value.loadFailed)
        val readsBefore = ledger.healthReads

        ledger.heal()
        vm.retry()
        advanceUntilIdle()

        assertTrue("retry must read the health again", ledger.healthReads > readsBefore)
        assertFalse(vm.state.value.loadFailed)
        assertEquals(listOf(1L), vm.state.value.rows.map { it.id })
    }

    @Test
    fun `a healthy ledger lists the entries`() = runTest {
        val vm = listOver(FakeEntryRepository(listOf(expense(1, day))))
        advanceUntilIdle()

        assertFalse(vm.state.value.loadFailed)
        assertEquals(listOf(1L), vm.state.value.rows.map { it.id })
    }

    @Test
    fun `list rows carry the entry id, one per entry, across days and kinds`() = runTest {
        val transfer = Transaction.Transfer(
            id = 3, sourceAccountId = 1, destAccountId = 2, sourceAmount = Money.of("9.00", "USD"),
            destAmount = Money.of("9.00", "USD"), rateUsed = java.math.BigDecimal.ONE, date = day.minusDays(1),
        )
        val vm = listOver(FakeEntryRepository(listOf(expense(1, day), expense(2, day), transfer)))
        advanceUntilIdle()

        val ids = vm.state.value.rows.map { it.id }
        assertEquals(listOf(2L, 1L, 3L), ids)
        assertEquals("a row key must be unique in the list", ids.size, ids.toSet().size)
    }

    /**
     * The row key is what lets the list keep its place while pages arrive and entries change, so it
     * has to be the entry id and the screen has to ask for the next page when its end comes into view.
     */
    @Test
    fun `the screen keys its rows by entry id and asks for the next page at the end`() {
        val screen = File("src/main/java/org/aristonis/mywallet/ui/transaction/TransactionsListScreen.kt").readText()

        assertTrue(
            "rows are not keyed by entry id",
            Regex("""items\(\s*[\w.]+\.rows\s*,\s*key\s*=\s*\{\s*(row\s*->\s*)?(it|row)\.id\s*\}""").containsMatchIn(screen),
        )
        assertTrue("the screen never asks for the next page", screen.contains("loadMore"))
    }
}
