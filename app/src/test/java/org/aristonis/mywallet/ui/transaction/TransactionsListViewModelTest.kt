package org.aristonis.mywallet.ui.transaction

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.RecordingErrorReporter
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.ThreadRecordingList
import org.aristonis.mywallet.namedWorkThread
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.aristonis.mywallet.ui.CategoryLabel
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/**
 * The list view model resolves ids to display names via a 3-way combine of transactions, accounts,
 * and categories. Tests pin: names resolve, a transfer shows BOTH account names + BOTH leg amounts,
 * rows are newest-first, and a missing id degrades to a dash instead of crashing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsListViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val cash = Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.zero("USD"))
    private val savings = Account(id = 2, name = "Savings", typeKey = "savings", currencyCode = "EUR", openingBalance = Money.zero("EUR"))
    private val salary = Category(id = 10, name = "Salary", kind = CategoryKind.INCOME)
    private val food = Category(id = 20, name = "Food", kind = CategoryKind.EXPENSE)

    // Locale.US + the seeded fraction digits give deterministic strings: USD 2, EUR 2, JPY 0.
    private val currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2), Currency("JPY", "¥", 0))

    private inner class ListFixture(
        transactions: List<Transaction>,
        accounts: List<Account>,
        categories: List<Category>,
    ) {
        val viewModel = TransactionsListViewModel(
            transactions = FakeTransactionRepository(transactions),
            accounts = FakeAccountRepository(accounts),
            categories = FakeCategoryRepository(categories),
            currencies = FakeCurrencyRepository(currencies),
            moneyFormatter = MoneyFormatter(Locale.US),
            today = { LocalDate.of(2026, 8, 26) },
            savedStateHandle = SavedStateHandle(),
            defaultDispatcher = dispatcher,
            errors = RecordingErrorReporter(),
        )
    }

    /** Builds the list and keeps a screen watching it, as the real tab does while it is shown. */
    @Suppress("TestFunctionName")
    private fun TestScope.Fixture(
        transactions: List<Transaction>,
        accounts: List<Account>,
        categories: List<Category>,
    ) = ListFixture(transactions, accounts, categories).also { watch(it.viewModel) }

    @Test
    fun incomeRow_resolvesAccountAndCategoryNames() = runTest {
        val f = Fixture(
            transactions = listOf(
                Transaction.Income(id = 1, accountId = 1, amount = Money.of("50", "USD"), categoryId = 10, date = LocalDate.of(2026, 7, 1)),
            ),
            accounts = listOf(cash),
            categories = listOf(salary),
        )
        advanceUntilIdle()

        val row = f.viewModel.state.value.rows.single()
        assertEquals(TransactionRowType.INCOME, row.type)
        assertEquals("Cash", row.accountName)
        assertEquals(CategoryLabel.Named("Salary"), row.categoryLabel)
        assertEquals(Money.of("50", "USD"), row.amount)
        assertEquals("50.00 USD", row.amountDisplay) // per-currency + locale, no sign (screen adds "+")
        assertNull(row.destAccountName)
        assertNull(row.destAmountDisplay)
    }

    @Test
    fun amountDisplay_isFormattedPerCurrencyAndLocale() = runTest {
        // The unsigned display string the screen renders as "+1,000.50 USD" (the "+" prefix is screen-side).
        val f = Fixture(
            transactions = listOf(
                Transaction.Income(id = 1, accountId = 1, amount = Money.of("1000.5", "USD"), categoryId = 10, date = LocalDate.of(2026, 7, 1)),
            ),
            accounts = listOf(cash),
            categories = listOf(salary),
        )
        advanceUntilIdle()

        assertEquals("1,000.50 USD", f.viewModel.state.value.rows.single().amountDisplay)
    }

    @Test
    fun expenseRow_resolvesAccountAndCategoryNames() = runTest {
        val f = Fixture(
            transactions = listOf(
                Transaction.Expense(id = 1, accountId = 1, amount = Money.of("30", "USD"), categoryId = 20, date = LocalDate.of(2026, 7, 1)),
            ),
            accounts = listOf(cash),
            categories = listOf(food),
        )
        advanceUntilIdle()

        val row = f.viewModel.state.value.rows.single()
        assertEquals(TransactionRowType.EXPENSE, row.type)
        assertEquals("Cash", row.accountName)
        assertEquals(CategoryLabel.Named("Food"), row.categoryLabel)
        assertEquals(Money.of("30", "USD"), row.amount)
    }

    @Test
    fun transferRow_showsBothAccountNames_andBothLegAmounts() = runTest {
        val f = Fixture(
            transactions = listOf(
                Transaction.Transfer(
                    id = 1, sourceAccountId = 1, destAccountId = 2,
                    sourceAmount = Money.of("11", "USD"), destAmount = Money.of("10.00", "EUR"),
                    rateUsed = BigDecimal("1.10"), date = LocalDate.of(2026, 7, 1),
                ),
            ),
            accounts = listOf(cash, savings),
            categories = emptyList(),
        )
        advanceUntilIdle()

        val row = f.viewModel.state.value.rows.single()
        assertEquals(TransactionRowType.TRANSFER, row.type)
        assertEquals("Cash", row.accountName)
        assertEquals("Savings", row.destAccountName)
        assertNull(row.categoryLabel)
        assertEquals(Money.of("11", "USD"), row.amount)
        assertEquals(Money.of("10.00", "EUR"), row.destAmount)
        // Both legs pre-formatted; the screen renders "−11.00 USD → +10.00 EUR".
        assertEquals("11.00 USD", row.amountDisplay)
        assertEquals("10.00 EUR", row.destAmountDisplay)
    }

    @Test
    fun transferWithJpyLeg_formatsThatLegWithNoDecimals() = runTest {
        // A JPY destination leg (0 fraction digits) renders "1,000 JPY" from the stored decimalPlaces.
        val f = Fixture(
            transactions = listOf(
                Transaction.Transfer(
                    id = 1, sourceAccountId = 1, destAccountId = 2,
                    sourceAmount = Money.of("6.7", "USD"), destAmount = Money.of("1000", "JPY"),
                    rateUsed = BigDecimal("149.25"), date = LocalDate.of(2026, 7, 1),
                ),
            ),
            accounts = listOf(cash, savings),
            categories = emptyList(),
        )
        advanceUntilIdle()

        val row = f.viewModel.state.value.rows.single()
        assertEquals("6.70 USD", row.amountDisplay)
        assertEquals("1,000 JPY", row.destAmountDisplay)
    }

    @Test
    fun rows_areNewestFirst_byDateThenId() = runTest {
        val f = Fixture(
            transactions = listOf(
                Transaction.Income(id = 1, accountId = 1, amount = Money.of("1", "USD"), categoryId = 10, date = LocalDate.of(2026, 7, 1)),
                Transaction.Income(id = 2, accountId = 1, amount = Money.of("2", "USD"), categoryId = 10, date = LocalDate.of(2026, 7, 3)),
                Transaction.Income(id = 3, accountId = 1, amount = Money.of("3", "USD"), categoryId = 10, date = LocalDate.of(2026, 7, 2)),
            ),
            accounts = listOf(cash),
            categories = listOf(salary),
        )
        advanceUntilIdle()

        assertEquals(listOf(2L, 3L, 1L), f.viewModel.state.value.rows.map { it.id })
    }

    @Test
    fun missingAccountId_degradesToAGap_withoutCrashing() = runTest {
        val f = Fixture(
            transactions = listOf(
                Transaction.Income(id = 1, accountId = 999, amount = Money.of("5", "USD"), categoryId = 888, date = LocalDate.of(2026, 7, 1)),
            ),
            accounts = listOf(cash), // no account 999
            categories = listOf(salary), // no category 888
        )
        advanceUntilIdle()

        val row = f.viewModel.state.value.rows.single()
        // The view model reports the gap; what a gap looks like is the screen's decision.
        assertNull(row.accountName)
        assertEquals(CategoryLabel.Unknown, row.categoryLabel)
    }

    @Test
    fun stopsReadingWhenNoOneWatches() = runTest {
        val repo = FakeTransactionRepository(
            listOf(Transaction.Expense(id = 1, accountId = 1, amount = Money.of("5", "USD"), categoryId = 20, date = LocalDate.of(2026, 8, 1))),
        )
        val viewModel = TransactionsListViewModel(
            transactions = repo,
            accounts = FakeAccountRepository(listOf(cash)),
            categories = FakeCategoryRepository(listOf(food)),
            currencies = FakeCurrencyRepository(currencies),
            moneyFormatter = MoneyFormatter(Locale.US),
            today = { LocalDate.of(2026, 8, 26) },
            savedStateHandle = SavedStateHandle(),
            defaultDispatcher = dispatcher,
            errors = RecordingErrorReporter(),
        )
        val screen = backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
        assertEquals(1, repo.activeReads)

        // The tab is hidden: after a short grace (a rotation must not restart the read) it lets go.
        screen.cancel()
        advanceTimeBy(STOP_GRACE_MS + 1)
        runCurrent()
        assertEquals(0, repo.activeReads)

        // Entries saved meanwhile show up as soon as the tab is back.
        repo.add(Transaction.Expense(id = 2, accountId = 1, amount = Money.of("7", "USD"), categoryId = 20, date = LocalDate.of(2026, 8, 2)))
        backgroundScope.launch { viewModel.state.collect {} }
        // Only background work is queued now, which advanceUntilIdle leaves alone; start it first.
        runCurrent()
        advanceUntilIdle()
        assertEquals(listOf(2L, 1L), viewModel.state.value.rows.map { it.id })
    }

    @Test
    fun buildsTheListOnTheWorkDispatcher() = runTest {
        val work = namedWorkThread(WORK_THREAD)
        // Every amount is formatted against this list, so whoever walks it is building the rows.
        val formattingData = ThreadRecordingList(currencies)
        val viewModel = TransactionsListViewModel(
            transactions = FakeTransactionRepository(
                listOf(Transaction.Expense(id = 1, accountId = 1, amount = Money.of("5", "USD"), categoryId = 20, date = LocalDate.of(2026, 8, 1))),
            ),
            accounts = FakeAccountRepository(listOf(cash)),
            categories = FakeCategoryRepository(listOf(food)),
            currencies = FakeCurrencyRepository(formattingData),
            moneyFormatter = MoneyFormatter(Locale.US),
            today = { LocalDate.of(2026, 8, 26) },
            savedStateHandle = SavedStateHandle(),
            defaultDispatcher = work,
            errors = RecordingErrorReporter(),
        )

        val built = viewModel.state.first { !it.isLoading }

        assertEquals(listOf(1L), built.rows.map { it.id })
        assertEquals(setOf(WORK_THREAD), formattingData.threads)
    }


    @Test
    fun aRotationDoesNotRestartTheRead() = runTest {
        val repo = FakeTransactionRepository(
            listOf(Transaction.Expense(id = 1, accountId = 1, amount = Money.of("5", "USD"), categoryId = 20, date = LocalDate.of(2026, 8, 1))),
        )
        val viewModel = TransactionsListViewModel(
            transactions = repo,
            accounts = FakeAccountRepository(listOf(cash)),
            categories = FakeCategoryRepository(listOf(food)),
            currencies = FakeCurrencyRepository(currencies),
            moneyFormatter = MoneyFormatter(Locale.US),
            today = { LocalDate.of(2026, 8, 26) },
            savedStateHandle = SavedStateHandle(),
            defaultDispatcher = dispatcher,
            errors = RecordingErrorReporter(),
        )
        val before = backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        // A rotation drops the screen's collector for a moment and the new screen picks it up again.
        before.cancel()
        advanceTimeBy(STOP_GRACE_MS - 1)
        backgroundScope.launch { viewModel.state.collect {} }
        runCurrent()
        advanceUntilIdle()

        assertEquals(1, repo.readsOpened)
    }


    private fun listOver(repo: FakeTransactionRepository, errors: RecordingErrorReporter = RecordingErrorReporter()) = TransactionsListViewModel(
        transactions = repo,
        accounts = FakeAccountRepository(listOf(cash)),
        categories = FakeCategoryRepository(listOf(food)),
        currencies = FakeCurrencyRepository(currencies),
        moneyFormatter = MoneyFormatter(Locale.US),
        today = { LocalDate.of(2026, 8, 26) },
        savedStateHandle = SavedStateHandle(),
        defaultDispatcher = dispatcher,
        errors = errors,
    )

    @Test
    fun aFailedReadShowsAnError() = runTest {
        val repo = FakeTransactionRepository(
            listOf(Transaction.Expense(id = 1, accountId = 1, amount = Money.of("5", "USD"), categoryId = 20, date = LocalDate.of(2026, 8, 1))),
        ).apply { unreadableRange = DateRange.ALL_TIME }
        val errors = RecordingErrorReporter()
        val viewModel = listOver(repo, errors)
        watch(viewModel)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.loadFailed)
        // Shown as a message, but never swallowed: the cause is kept for whoever diagnoses it.
        assertEquals(listOf("a stored row could not be read"), errors.reported.map { it.message })
        assertFalse(state.isLoading)
        assertEquals(emptyList<TransactionRow>(), state.rows)
    }

    @Test
    fun aFailedReadRecoversWhenTheDatesChange() = runTest {
        // The failure belongs to the dates it was read for; picking other dates reads again.
        val repo = FakeTransactionRepository(
            listOf(Transaction.Expense(id = 1, accountId = 1, amount = Money.of("5", "USD"), categoryId = 20, date = LocalDate.of(2026, 8, 1))),
        ).apply { unreadableRange = DateRange.ALL_TIME }
        val viewModel = listOver(repo)
        watch(viewModel)
        advanceUntilIdle()

        viewModel.windowActions.onSelectPeriod(TrackingPeriod.MONTH)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.loadFailed)
        assertEquals(TrackingPeriod.MONTH, (state.window as TrackingWindow.Period).period)
        assertEquals(listOf(1L), state.rows.map { it.id })
    }

    private companion object {
        const val STOP_GRACE_MS = 5_000L
        const val WORK_THREAD = "work-test"
    }
}
