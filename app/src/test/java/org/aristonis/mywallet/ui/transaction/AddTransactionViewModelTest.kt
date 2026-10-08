package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.domain.usecase.RecordWithTypedRates
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.usecase.SetExchangeRate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.usecase.GetAccountBalances
import org.aristonis.mywallet.domain.usecase.RecordExpense
import org.aristonis.mywallet.domain.usecase.RecordIncome
import org.aristonis.mywallet.domain.usecase.RecordTransfer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.aristonis.mywallet.ui.message.UiMessage
import java.time.LocalDate
import java.util.Locale

/**
 * The real bar here is NOT "a row was added" — it is that recording a transaction MOVES the balances
 * the rest of the app reads. So the probe is a real [GetAccountBalances] wired over the SAME fake
 * repositories the view model writes through: record income -> that account's balance rises, etc.
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
        currencies: List<Currency> = emptyList(),
        rates: List<ExchangeRate> = emptyList(),
        base: String = "USD",
        today: LocalDate = LocalDate.of(2026, 7, 3),
    ) {
        val accountRepo = FakeAccountRepository(accounts)
        val categoryRepo = FakeCategoryRepository(categories)
        val txRepo = FakeTransactionRepository()
        val currencyRepo = FakeCurrencyRepository(currencies)
        val rateRepo = FakeRateRepository(rates)
        val settingsRepo = FakeSettingsRepository(Settings(baseCurrencyCode = base))
        val balances = GetAccountBalances(accountRepo, txRepo)
        val viewModel = AddTransactionViewModel(
            accounts = accountRepo,
            categories = categoryRepo,
            recordIncome = RecordIncome(accountRepo, categoryRepo, txRepo),
            recordExpense = RecordExpense(accountRepo, categoryRepo, txRepo),
            recordTransfer = RecordTransfer(accountRepo, currencyRepo, rateRepo, settingsRepo, txRepo),
            today = TodayProvider { today },
            moneyParser = MoneyParser(Locale.US),
            fx = FakeFxRepository(currencyRepo, rateRepo, settingsRepo),
            settings = settingsRepo,
            moneyFormatter = MoneyFormatter(Locale.US),
            recordWithTypedRates = RecordWithTypedRates(DirectTransactionRunner(), SetExchangeRate(FakeCurrencyRepository(), FakeRateRepository())),
        )

        suspend fun balanceOf(accountId: Long): Money =
            balances().first().first { it.account.id == accountId }.balance
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
        assertTrue(f.viewModel.state.value.saved)
    }

    @Test
    fun archivedAccounts_areExcludedFromThePickers_andNeverPreSelected() = runTest {
        // Archived account listed FIRST so a naive firstOrNull() pre-select would pick it. Recording
        // against an archived account would move money the rest of the app excludes from net worth.
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
        assertTrue(f.viewModel.state.value.saved)
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
        assertTrue(f.viewModel.state.value.saved)
    }

    @Test
    fun crossCurrencyTransfer_withRate_creditsConvertedAmountToDest() = runTest {
        // base USD; EUR rate 1.10. Transfer 11 USD -> EUR account = 10.00 EUR (RecordTransferTest fixture).
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
        assertTrue(f.viewModel.state.value.saved)
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
        assertFalse(f.viewModel.state.value.saved)
        assertTrue(f.txRepo.added.isEmpty())
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
        assertFalse(f.viewModel.state.value.saved)
        assertTrue(f.txRepo.added.isEmpty())
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
        assertTrue(f.txRepo.added.isEmpty())
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
        assertTrue(f.txRepo.added.isEmpty())
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
        // The view model is retained across the Home toggle (no nav back-stack), so a stale saved flag
        // would bounce a re-opened screen straight back. acknowledgeSaved resets it after navigating.
        val f = Fixture(accounts = listOf(account(1, "USD", opening = "100")), categories = listOf(food))
        advanceUntilIdle()

        f.viewModel.selectType(TransactionType.EXPENSE)
        f.viewModel.selectAccount(1)
        f.viewModel.selectCategory(food.id)
        f.viewModel.setAmount("30")
        f.viewModel.submit()
        advanceUntilIdle()
        assertTrue(f.viewModel.state.value.saved)

        f.viewModel.acknowledgeSaved()
        assertFalse(f.viewModel.state.value.saved)
    }

    @Test
    fun acknowledgeSaved_resetsForm_andReReadsToday() = runTest {
        // After a save, the form must clear and — critically — the date must re-read "today" so a
        // backdated entry does not silently carry into the next transaction.
        var todayValue = LocalDate.of(2026, 1, 1)
        val accountRepo = FakeAccountRepository(listOf(account(1, "USD", opening = "100")))
        val categoryRepo = FakeCategoryRepository(listOf(food))
        val txRepo = FakeTransactionRepository()
        val vm = AddTransactionViewModel(
            accounts = accountRepo,
            categories = categoryRepo,
            recordIncome = RecordIncome(accountRepo, categoryRepo, txRepo),
            recordExpense = RecordExpense(accountRepo, categoryRepo, txRepo),
            recordTransfer = RecordTransfer(
                accountRepo, FakeCurrencyRepository(emptyList()),
                FakeRateRepository(emptyList()), FakeSettingsRepository(Settings(baseCurrencyCode = "USD")), txRepo,
            ),
            today = TodayProvider { todayValue },
            moneyParser = MoneyParser(Locale.US),
            fx = FakeFxRepository(FakeCurrencyRepository(), FakeRateRepository(), FakeSettingsRepository()),
            settings = FakeSettingsRepository(),
            moneyFormatter = MoneyFormatter(Locale.US),
            recordWithTypedRates = RecordWithTypedRates(DirectTransactionRunner(), SetExchangeRate(FakeCurrencyRepository(), FakeRateRepository())),
        )
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
        assertFalse(s.saved)
        assertEquals("", s.amountInput)
        assertEquals("", s.note)
        assertEquals(null, s.selectedCategoryId)
        assertEquals(LocalDate.of(2026, 2, 2), s.date)
    }

    @Test
    fun date_defaultsToInjectedToday() = runTest {
        val f = Fixture(accounts = listOf(account(1, "USD")), today = LocalDate.of(2026, 1, 15))
        advanceUntilIdle()

        assertEquals(LocalDate.of(2026, 1, 15), f.viewModel.state.value.date)
    }
}
