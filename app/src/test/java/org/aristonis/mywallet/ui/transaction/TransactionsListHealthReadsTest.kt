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
import org.aristonis.mywallet.ui.HealthLedger
import org.aristonis.mywallet.ui.WriteTickingLedger
import org.aristonis.mywallet.ui.message.UiMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * The list is rebuilt once per change to its entries, not again for every health answer, and a
 * health read that fails for a reason other than an unreadable row shows the generic failure.
 *
 * Assumed API: `TransactionsUiState.loadFailure: UiMessage?`, null while nothing failed, and when
 * `loadFailed` is true `UiMessage.DataUnreadable` for an unreadable row and `UiMessage.Unexpected`
 * for any other failure; the screen words the two differently. `loadFailed` keeps its meaning.
 * A failing health read other than an unreadable row ends in the failed state, never an app crash.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsListHealthReadsTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val day = LocalDate.of(2026, 8, 20)
    private val cash = Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.zero("USD"))
    private val food = Category(id = 20, name = "Food", kind = CategoryKind.EXPENSE)
    private val errors = RecordingErrorReporter()

    private fun expense(id: Long) =
        Transaction.Expense(id = id, accountId = 1, amount = Money.of("5.00", "USD"), categoryId = 20, date = day)

    /** A currency list that counts how often a row looks a currency up, i.e. how many rows were built. */
    private class CountingCurrencies(private val inner: List<Currency>) : AbstractList<Currency>() {
        var lookups = 0
            private set
        override val size: Int get() = inner.size
        override fun get(index: Int): Currency {
            lookups++
            return inner[index]
        }
    }

    private fun TestScope.listOver(
        entries: FakeEntryRepository,
        ledger: HealthLedger,
        currencies: List<Currency> = listOf(Currency("USD", "$", 2)),
    ) = TransactionsListViewModel(
        entries = entries,
        accounts = FakeAccountRepository(listOf(cash)),
        categories = FakeCategoryRepository(listOf(food)),
        currencies = FakeCurrencyRepository(currencies),
        settings = FakeSettingsRepository(org.aristonis.mywallet.domain.model.Settings("USD")),
        observeLedgerHealth = ledger.health(),
        moneyFormatter = MoneyFormatter(Locale.US),
        today = { day },
        savedStateHandle = SavedStateHandle(),
        defaultDispatcher = dispatcher,
        errors = errors,
    ).also { watch(it) }

    @Test
    fun `a health answer that changes nothing builds no rows again`() = runTest {
        val ledger = WriteTickingLedger()
        val counting = CountingCurrencies(listOf(Currency("USD", "$", 2)))
        listOver(FakeEntryRepository(listOf(expense(1))), ledger, counting)
        advanceUntilIdle()
        val afterFirstBuild = counting.lookups
        assertEquals("one row built once", 1, afterFirstBuild)

        ledger.write()
        advanceUntilIdle()
        ledger.write()
        advanceUntilIdle()

        assertEquals("two healthy writes rebuilt the page", afterFirstBuild, counting.lookups)
    }

    @Test
    fun `a write to the entries builds the page once even though the health answers too`() = runTest {
        val ledger = WriteTickingLedger()
        val entries = FakeEntryRepository(listOf(expense(1)))
        val counting = CountingCurrencies(listOf(Currency("USD", "$", 2)))
        listOver(entries, ledger, counting)
        advanceUntilIdle()
        val before = counting.lookups

        entries.insert(expense(0))
        advanceUntilIdle()
        ledger.write()
        advanceUntilIdle()

        assertEquals("two rows, built once", before + 2, counting.lookups)
    }

    @Test
    fun `a health read that fails for another reason shows the generic failure`() = runTest {
        val ledger = HealthLedger()
        val failure = IllegalStateException("disk I/O error")
        ledger.healthFailure = failure

        val vm = listOver(FakeEntryRepository(listOf(expense(1))), ledger)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.loadFailed)
        assertEquals(UiMessage.Unexpected, state.loadFailure)
        assertSame(failure, errors.reported.single())
    }

    @Test
    fun `an unreadable row still shows the cannot-be-read failure`() = runTest {
        val ledger = HealthLedger()
        ledger.plantUnknownKind()

        val vm = listOver(FakeEntryRepository(listOf(expense(1))), ledger)
        advanceUntilIdle()

        assertTrue(vm.state.value.loadFailed)
        assertEquals(UiMessage.DataUnreadable, vm.state.value.loadFailure)
    }

    @Test
    fun `Retry after a generic health failure lists the entries and clears the failure`() = runTest {
        val ledger = HealthLedger()
        ledger.healthFailure = java.io.IOException("disk I/O error")
        val vm = listOver(FakeEntryRepository(listOf(expense(1))), ledger)
        advanceUntilIdle()

        ledger.healthFailure = null
        vm.retry()
        advanceUntilIdle()

        assertEquals(listOf(1L), vm.state.value.rows.map { it.id })
        assertNull(vm.state.value.loadFailure)
    }

    @Test
    fun `a healthy list has no load failure`() = runTest {
        val vm = listOver(FakeEntryRepository(listOf(expense(1))), HealthLedger())
        advanceUntilIdle()

        assertNull(vm.state.value.loadFailure)
    }
}
