package org.aristonis.mywallet.ui.transaction

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * The history is read a day at a time, so the list is grouped by day rather than left as one flat
 * run of rows. "Today" and "Yesterday" are named relative to the device's current date, which is why
 * the view model needs to be told what day it is instead of asking a clock directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsListSectionsTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val today = LocalDate.of(2026, 8, 26)
    private val cash = Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.zero("USD"))
    private val food = Category(id = 20, name = "Food", kind = CategoryKind.EXPENSE)
    private val currencies = listOf(Currency("USD", "$", 2))

    private fun expenseOn(id: Long, date: LocalDate) = Transaction.Expense(
        id = id, accountId = 1, amount = Money.of("$id.00", "USD"), categoryId = 20, date = date,
    )

    private fun TestScope.viewModelFor(transactions: List<Transaction>) = TransactionsListViewModel(
        transactions = FakeTransactionRepository(transactions),
        accounts = FakeAccountRepository(listOf(cash)),
        categories = FakeCategoryRepository(listOf(food)),
        currencies = FakeCurrencyRepository(currencies),
        moneyFormatter = MoneyFormatter(Locale.US),
        today = { today },
        savedStateHandle = SavedStateHandle(),
        defaultDispatcher = dispatcher,
    ).also { watch(it) }

    @Test
    fun `rows from the same day land in one section`() = runTest {
        val vm = viewModelFor(
            listOf(expenseOn(1, today), expenseOn(2, today), expenseOn(3, today.minusDays(1))),
        )
        advanceUntilIdle()

        val sections = vm.state.value.sections
        assertEquals(2, sections.size)
        assertEquals(2, sections[0].rows.size)
        assertEquals(1, sections[1].rows.size)
    }

    @Test
    fun `sections run newest day first`() = runTest {
        val vm = viewModelFor(
            listOf(expenseOn(1, today.minusDays(5)), expenseOn(2, today), expenseOn(3, today.minusDays(1))),
        )
        advanceUntilIdle()

        assertEquals(
            listOf(today, today.minusDays(1), today.minusDays(5)),
            vm.state.value.sections.map { it.date },
        )
    }

    @Test
    fun `today and yesterday are named relative to the current date`() = runTest {
        val vm = viewModelFor(
            listOf(expenseOn(1, today), expenseOn(2, today.minusDays(1)), expenseOn(3, today.minusDays(2))),
        )
        advanceUntilIdle()

        assertEquals(
            listOf(DayRelation.TODAY, DayRelation.YESTERDAY, DayRelation.EARLIER),
            vm.state.value.sections.map { it.relation },
        )
    }

    /**
     * A transaction can be recorded with a future date. Calling that "today" would be wrong, and
     * silently dropping it would lose money from the history.
     */
    @Test
    fun `a future day is kept and is not called today`() = runTest {
        val vm = viewModelFor(listOf(expenseOn(1, today.plusDays(3)), expenseOn(2, today)))
        advanceUntilIdle()

        val sections = vm.state.value.sections
        assertEquals(listOf(today.plusDays(3), today), sections.map { it.date })
        assertEquals(DayRelation.EARLIER, sections[0].relation)
        assertEquals(DayRelation.TODAY, sections[1].relation)
    }

    /** The flat view still reads newest-first, so nothing that depended on it has to change. */
    @Test
    fun `the flat row list is the sections in order`() = runTest {
        val vm = viewModelFor(listOf(expenseOn(1, today.minusDays(1)), expenseOn(2, today)))
        advanceUntilIdle()

        assertEquals(listOf(2L, 1L), vm.state.value.rows.map { it.id })
    }

    @Test
    fun `an empty history has no sections`() = runTest {
        val vm = viewModelFor(emptyList())
        advanceUntilIdle()

        assertEquals(emptyList<TransactionSection>(), vm.state.value.sections)
        assertEquals(false, vm.state.value.isLoading)
    }
}
