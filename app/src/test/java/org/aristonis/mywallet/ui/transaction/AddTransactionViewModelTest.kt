package org.aristonis.mywallet.ui.transaction

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.port.AccountLeg
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.usecase.GetAccountBalances
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.navigation.ARG_DEBT_SIDE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * The real bar here is NOT "a row was added" — it is that recording a transaction MOVES the balances
 * the rest of the app reads. So the probe is a real [GetAccountBalances] reading the account legs of
 * the SAME fake entry store the view model saves into: record income -> that account's balance rises.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddTransactionViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val usd = Currency("USD", "$", 2)
    private val eur = Currency("EUR", "€", 2)
    private val salary = Category(id = 1, name = "Salary", kind = CategoryKind.INCOME)
    private val food = Category(id = 2, name = "Food", kind = CategoryKind.EXPENSE)

    private fun account(id: Long, currency: String, opening: String = "0") = Account(
        id = id, name = "acct-$id", typeKey = "cash", currencyCode = currency,
        openingBalance = Money.of(opening, currency),
    )

    private class Fixture(
        accounts: List<Account>,
        categories: List<Category> = emptyList(),
        currencies: List<Currency> = listOf(Currency("USD", "$", 2)),
        rates: List<ExchangeRate> = emptyList(),
        base: String = "USD",
        today: () -> LocalDate = { LocalDate.of(2026, 7, 3) },
        initialDebtSide: DebtSide? = null,
    ) {
        val accountRepo = FakeAccountRepository(accounts)
        val categoryRepo = FakeCategoryRepository(categories)
        val currencyRepo = FakeCurrencyRepository(currencies)
        val rateRepo = FakeRateRepository(rates)
        val settingsRepo = FakeSettingsRepository(Settings(baseCurrencyCode = base))
        val use = EntryUseCases(accountRepo, categoryRepo, FakeEntryRepository(), currencyRepo, rateRepo, settingsRepo)
        val balances = GetAccountBalances(accountRepo, AccountLegsOf(use.entries))
        val viewModel = AddTransactionViewModel(
            accounts = accountRepo,
            categories = categoryRepo,
            saveEntry = use.saveEntry,
            today = TodayProvider { today() },
            moneyParser = MoneyParser(Locale.US),
            fx = FakeFxRepository(currencyRepo, rateRepo, settingsRepo),
            settings = settingsRepo,
            moneyFormatter = MoneyFormatter(Locale.US),
            suggestCounterparties = org.aristonis.mywallet.domain.usecase.SuggestCounterparties(use.debts),
            debts = use.debts,
            deductionItems = FakeDeductionItemRepository(),
            categoryRoles = FakeFormRoles(),
            savedStateHandle = SavedStateHandle(initialDebtSide?.let { mapOf(ARG_DEBT_SIDE to it.name) } ?: emptyMap()),
        )

        /** Everything the save stored. */
        val stored get() = use.entries.stored

        suspend fun balanceOf(accountId: Long): Money =
            balances().first().first { it.account.id == accountId }.balance
    }

    @Test
    fun debtAddStartsInTheSideSelectedOnTheDebtListAndKeepsOwnerChanges() = runTest {
        val owing = Fixture(accounts = listOf(account(1, "USD")), initialDebtSide = DebtSide.I_OWE)
        val receivable = Fixture(accounts = listOf(account(2, "USD")), initialDebtSide = DebtSide.OWED_TO_ME)

        assertEquals(TransactionType.BORROW, owing.viewModel.state.value.type)
        assertEquals(DebtSide.I_OWE, owing.viewModel.state.value.debtSide)
        assertEquals(TransactionType.LEND, receivable.viewModel.state.value.type)
        assertEquals(DebtSide.OWED_TO_ME, receivable.viewModel.state.value.debtSide)

        receivable.viewModel.selectType(TransactionType.INCOME)
        advanceUntilIdle()
        assertEquals(TransactionType.INCOME, receivable.viewModel.state.value.type)
    }

    /** The account legs of whatever the entry store holds, for the balance probe; nothing else is read. */
    private class AccountLegsOf(private val entries: FakeEntryRepository) : LedgerQueries by FakeLedgerQueries() {
        override fun observeAccountLegs(): Flow<List<AccountLeg>> = entries.observeAll().map { all ->
            all.flatMap { entry ->
                entry.toParts().legs.mapNotNull { leg ->
                    (leg.target as? LegTarget.Account)?.let { AccountLeg(it.id, leg.amount) }
                }
            }
        }
    }

    @Test
    fun recordIncome_raisesThatAccountsBalance() = runTest {
        val f = Fixture(accounts = listOf(account(1, "USD", opening = "100")), categories = listOf(salary))
        advanceUntilIdle()

        f.viewModel.selectType(TransactionType.INCOME)
        f.viewModel.selectAccount(1)
        f.viewModel.selectCategory(salary.id)
        f.viewModel.setAmount("50")
        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(Money.of("150", "USD"), f.balanceOf(1))
        assertNotNull(f.viewModel.state.value.savedOn)
    }

    @Test
    fun archivedAccounts_areExcludedFromThePickers_andNeverPreSelected() = runTest {
        // Archived account listed FIRST so a naive firstOrNull() pre-select would pick it. Recording
        // against an archived account would move money the rest of the app excludes from Home.
        val f = Fixture(
            accounts = listOf(account(1, "USD").copy(archived = true), account(2, "USD")),
            currencies = listOf(usd),
        )
        advanceUntilIdle()

        val state = f.viewModel.state.value
        assertEquals(listOf(2L), state.accounts.map { it.id }) // archived source hidden
        assertFalse(state.destAccounts.any { it.id == 1L })    // archived not a transfer destination
        assertEquals(2L, state.selectedAccountId)              // pre-select skips the archived account
    }

    @Test
    fun recordExpense_lowersThatAccountsBalance() = runTest {
        val f = Fixture(accounts = listOf(account(1, "USD", opening = "100")), categories = listOf(food))
        advanceUntilIdle()

        f.viewModel.selectType(TransactionType.EXPENSE)
        f.viewModel.selectAccount(1)
        f.viewModel.selectCategory(food.id)
        f.viewModel.setAmount("30")
        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(Money.of("70", "USD"), f.balanceOf(1))
        assertNotNull(f.viewModel.state.value.savedOn)
    }

    @Test
    fun sameCurrencyTransfer_movesFromSourceToDest() = runTest {
        val f = Fixture(
            accounts = listOf(account(1, "USD", opening = "100"), account(2, "USD", opening = "0")),
            currencies = listOf(usd),
        )
        advanceUntilIdle()

        f.viewModel.selectType(TransactionType.TRANSFER)
        f.viewModel.selectAccount(1)
        f.viewModel.selectDestAccount(2)
        f.viewModel.setAmount("40")
        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(Money.of("60", "USD"), f.balanceOf(1))
        assertEquals(Money.of("40", "USD"), f.balanceOf(2))
        assertNotNull(f.viewModel.state.value.savedOn)
    }

    @Test
    fun crossCurrencyTransfer_withRate_creditsConvertedAmountToDest() = runTest {
        // base USD; EUR rate 1.10. Transfer 11 USD -> EUR account = 10.00 EUR (the SaveEntryNewTransferTest fixture).
        val f = Fixture(
            accounts = listOf(account(1, "USD", opening = "100"), account(2, "EUR", opening = "0")),
            currencies = listOf(usd, eur),
            rates = listOf(ExchangeRate("EUR", java.math.BigDecimal("1.10"))),
        )
        advanceUntilIdle()

        f.viewModel.selectType(TransactionType.TRANSFER)
        f.viewModel.selectAccount(1)
        f.viewModel.selectDestAccount(2)
        f.viewModel.setAmount("11")
        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(Money.of("89", "USD"), f.balanceOf(1))
        assertEquals(Money.of("10.00", "EUR"), f.balanceOf(2))
        assertNotNull(f.viewModel.state.value.savedOn)
    }

    @Test
    fun crossCurrencyTransfer_missingRate_failsLoud_nothingPersisted() = runTest {
        val f = Fixture(
            accounts = listOf(account(1, "USD", opening = "100"), account(2, "EUR", opening = "0")),
            currencies = listOf(usd, eur),
            rates = emptyList(),
        )
        advanceUntilIdle()

        f.viewModel.selectType(TransactionType.TRANSFER)
        f.viewModel.selectAccount(1)
        f.viewModel.selectDestAccount(2)
        f.viewModel.setAmount("11")
        f.viewModel.submit()
        advanceUntilIdle()

        assertNotNull(f.viewModel.state.value.error)
        assertNull(f.viewModel.state.value.savedOn)
        assertTrue(f.stored.isEmpty())
        // Balances untouched.
        assertEquals(Money.of("100", "USD"), f.balanceOf(1))
        assertEquals(Money.of("0", "EUR"), f.balanceOf(2))
    }

    @Test
    fun submit_blankAmount_failsLoud_withoutRecording() = runTest {
        val f = Fixture(accounts = listOf(account(1, "USD", opening = "100")), categories = listOf(food))
        advanceUntilIdle()

        f.viewModel.selectType(TransactionType.EXPENSE)
        f.viewModel.selectAccount(1)
        f.viewModel.selectCategory(food.id)
        f.viewModel.setAmount("   ")
        f.viewModel.submit()
        advanceUntilIdle()

        assertNotNull(f.viewModel.state.value.error)
        assertNull(f.viewModel.state.value.savedOn)
        assertTrue(f.stored.isEmpty())
    }

    @Test
    fun submit_nonPositiveAmount_failsLoud_withoutRecording() = runTest {
        val f = Fixture(accounts = listOf(account(1, "USD", opening = "100")), categories = listOf(food))
        advanceUntilIdle()

        f.viewModel.selectType(TransactionType.EXPENSE)
        f.viewModel.selectAccount(1)
        f.viewModel.selectCategory(food.id)
        f.viewModel.setAmount("0")
        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(UiMessage.AmountNotPositive, f.viewModel.state.value.error)
        assertTrue(f.stored.isEmpty())
    }

    @Test
    fun submit_unparseableAmount_failsLoud_withoutRecording() = runTest {
        val f = Fixture(accounts = listOf(account(1, "USD", opening = "100")), categories = listOf(food))
        advanceUntilIdle()

        f.viewModel.selectType(TransactionType.EXPENSE)
        f.viewModel.selectAccount(1)
        f.viewModel.selectCategory(food.id)
        f.viewModel.setAmount("not-a-number")
        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(UiMessage.NotANumber, f.viewModel.state.value.error)
        assertTrue(f.stored.isEmpty())
    }

    @Test
    fun incomeForm_listsOnlyIncomeCategories_expenseListsOnlyExpense() = runTest {
        val f = Fixture(
            accounts = listOf(account(1, "USD")),
            categories = listOf(salary, food),
        )
        advanceUntilIdle()

        f.viewModel.selectType(TransactionType.INCOME)
        assertEquals(listOf(salary), f.viewModel.state.value.categoriesForType)

        f.viewModel.selectType(TransactionType.EXPENSE)
        assertEquals(listOf(food), f.viewModel.state.value.categoriesForType)
    }

    @Test
    fun changingType_clearsSelectedCategory() = runTest {
        val f = Fixture(accounts = listOf(account(1, "USD")), categories = listOf(salary, food))
        advanceUntilIdle()

        f.viewModel.selectType(TransactionType.INCOME)
        f.viewModel.selectCategory(salary.id)
        assertEquals(salary.id, f.viewModel.state.value.selectedCategoryId)

        f.viewModel.selectType(TransactionType.EXPENSE)
        assertEquals(null, f.viewModel.state.value.selectedCategoryId)
    }

    @Test
    fun acknowledgeSaved_clearsTheOneShotSignal() = runTest {
        // A retained view model with a stale savedOn would bounce a re-opened screen straight back.
        // acknowledgeSaved resets it after navigating.
        val f = Fixture(accounts = listOf(account(1, "USD", opening = "100")), categories = listOf(food))
        advanceUntilIdle()

        f.viewModel.selectType(TransactionType.EXPENSE)
        f.viewModel.selectAccount(1)
        f.viewModel.selectCategory(food.id)
        f.viewModel.setAmount("30")
        f.viewModel.submit()
        advanceUntilIdle()
        assertNotNull(f.viewModel.state.value.savedOn)

        f.viewModel.acknowledgeSaved()
        assertNull(f.viewModel.state.value.savedOn)
    }

    @Test
    fun acknowledgeSaved_resetsForm_andReReadsToday() = runTest {
        // After a save, the form must clear and — critically — the date must re-read "today" so a
        // backdated entry does not silently carry into the next transaction.
        var todayValue = LocalDate.of(2026, 1, 1)
        val vm = Fixture(
            accounts = listOf(account(1, "USD", opening = "100")),
            categories = listOf(food),
            currencies = listOf(usd),
            today = { todayValue },
        ).viewModel
        advanceUntilIdle()

        vm.selectType(TransactionType.EXPENSE)
        vm.selectAccount(1)
        vm.selectCategory(food.id)
        vm.setAmount("30")
        vm.setNote("lunch")
        vm.submit()
        advanceUntilIdle()

        todayValue = LocalDate.of(2026, 2, 2) // a day passes before the next entry
        vm.acknowledgeSaved()

        val s = vm.state.value
        assertNull(s.savedOn)
        assertEquals("", s.amountInput)
        assertEquals("", s.note)
        assertEquals(null, s.selectedCategoryId)
        assertEquals(LocalDate.of(2026, 2, 2), s.date)
    }

    @Test
    fun date_defaultsToInjectedToday() = runTest {
        val f = Fixture(accounts = listOf(account(1, "USD")), today = { LocalDate.of(2026, 1, 15) })
        advanceUntilIdle()

        assertEquals(LocalDate.of(2026, 1, 15), f.viewModel.state.value.date)
    }
}
