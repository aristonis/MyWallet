package org.aristonis.mywallet.ui.transaction

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
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private inner class Fixture(
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
        )
    }

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
}
