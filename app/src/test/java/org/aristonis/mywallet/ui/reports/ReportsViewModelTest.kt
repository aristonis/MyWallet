package org.aristonis.mywallet.ui.reports

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.aristonis.mywallet.domain.model.DateRange
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.ThreadRecordingList
import org.aristonis.mywallet.namedWorkThread
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.port.TransactionRepository
import org.aristonis.mywallet.domain.usecase.ComputeCategoryBreakdown
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.aristonis.mywallet.ui.CategoryLabel
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import org.aristonis.mywallet.ui.transaction.FakeFxRepository

@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val julRef = LocalDate.of(2026, 7, 15)

    // Locale.US pins grouping/decimal separators so the formatted-string assertions are deterministic.
    private val moneyFormatter = MoneyFormatter(Locale.US)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun usd(a: String) = Money.of(a, "USD")
    private fun eur(a: String) = Money.of(a, "EUR")

    private val usdEur = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2))

    private var now = julRef

    private fun buildVm(
        transactions: List<Transaction>,
        categories: List<Category> = emptyList(),
        currencies: List<Currency> = usdEur,
        rateRepo: RateRepository = FakeRateRepository(emptyList()),
        base: String = "USD",
        savedState: SavedStateHandle = SavedStateHandle(),
        txRepo: FakeTransactionRepository = FakeTransactionRepository(transactions),
        work: CoroutineDispatcher = dispatcher,
    ): ReportsViewModel {
        val currencyRepo = FakeCurrencyRepository(currencies)
        val settingsRepo = FakeSettingsRepository(Settings(baseCurrencyCode = base))
        val categoryRepo = FakeCategoryRepository(categories)
        return ReportsViewModel(
            computeCategoryBreakdown = ComputeCategoryBreakdown(txRepo, FakeFxRepository(currencyRepo, rateRepo, settingsRepo)),
            categories = categoryRepo,
            currencies = currencyRepo,
            moneyFormatter = moneyFormatter,
            today = TodayProvider { now },
            savedStateHandle = savedState,
            defaultDispatcher = work,
        )
    }

    private fun income(amount: Money, category: Long, date: LocalDate, sub: Long? = null) =
        Transaction.Income(id = 0, accountId = 1, amount = amount, categoryId = category, subCategoryId = sub, date = date)

    private fun expense(amount: Money, category: Long, date: LocalDate, sub: Long? = null) =
        Transaction.Expense(id = 0, accountId = 1, amount = amount, categoryId = category, subCategoryId = sub, date = date)

    private fun ready(state: ReportsUiState): ReportsData.Ready = state.data as ReportsData.Ready

    @Test
    fun defaultPeriodIsMonth_andSummarizesInPeriodIncomeExpenseNet() = runTest {
        val vm = buildVm(
            transactions = listOf(
                income(usd("100"), category = 1, date = LocalDate.of(2026, 7, 10)),
                expense(usd("30"), category = 2, date = LocalDate.of(2026, 7, 20)),
            ),
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(TrackingWindow.Period(TrackingPeriod.MONTH, julRef), state.window)
        val data = ready(state)
        assertEquals("100.00 USD", data.incomeDisplay)
        assertEquals("30.00 USD", data.expenseDisplay)
        assertEquals("70.00 USD", data.netDisplay)
    }

    @Test
    fun summaryAmounts_areFormattedPerCurrencyAndLocale() = runTest {
        // Thousands grouping + two-decimal padding: 1000.5 income renders "1,000.50 USD".
        val vm = buildVm(
            transactions = listOf(income(usd("1000.5"), category = 1, date = LocalDate.of(2026, 7, 10))),
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals("1,000.50 USD", ready(vm.state.value).incomeDisplay)
    }

    @Test
    fun categoryBreakdown_resolvesNames_splitsIncomeFromExpense_sortedByTotalDesc() = runTest {
        val vm = buildVm(
            transactions = listOf(
                expense(usd("15"), category = 9, date = LocalDate.of(2026, 7, 5)),
                expense(usd("30"), category = 7, date = LocalDate.of(2026, 7, 6)),
                expense(usd("20"), category = 7, date = LocalDate.of(2026, 7, 7)),
                income(usd("500"), category = 1, date = LocalDate.of(2026, 7, 8)),
            ),
            categories = listOf(
                Category(id = 7, name = "Food", kind = CategoryKind.EXPENSE),
                Category(id = 9, name = "Transport", kind = CategoryKind.EXPENSE),
                Category(id = 1, name = "Salary", kind = CategoryKind.INCOME),
            ),
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(listOf(CategoryLabel.Named("Salary")), ready(vm.state.value).incomeCategories.map { it.label })
        val rows = ready(vm.state.value).expenseCategories
        assertEquals(listOf(CategoryLabel.Named("Food"), CategoryLabel.Named("Transport")), rows.map { it.label }) // 50 before 15 (desc)
        assertEquals("50.00 USD", rows[0].totalDisplay)
        assertEquals("15.00 USD", rows[1].totalDisplay)
    }

    @Test
    fun unknownCategoryId_degradesToDash() = runTest {
        val vm = buildVm(
            transactions = listOf(expense(usd("10"), category = 99, date = LocalDate.of(2026, 7, 5))),
            categories = emptyList(),
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(CategoryLabel.Unknown, ready(vm.state.value).expenseCategories.single().label)
    }

    @Test
    fun twoUnknownCategoryIds_produceRowsWithDistinctKeys() = runTest {
        // Both ids are unknown so both render "—"; the rows must still carry distinct ids, otherwise the
        // LazyColumn (keyed on id) would collide on a duplicate key and crash.
        val vm = buildVm(
            transactions = listOf(
                expense(usd("10"), category = 98, date = LocalDate.of(2026, 7, 5)),
                expense(usd("20"), category = 99, date = LocalDate.of(2026, 7, 6)),
            ),
            categories = emptyList(),
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        val rows = ready(vm.state.value).expenseCategories
        assertEquals(listOf(CategoryLabel.Unknown, CategoryLabel.Unknown), rows.map { it.label })
        assertEquals(setOf(98L, 99L), rows.map { it.id }.toSet()) // distinct keys despite identical names
    }

    @Test
    fun changingPeriod_recomputes() = runTest {
        // A single income on Jul 10 — inside July (MONTH) but not on the reference day (DAY = Jul 15).
        val vm = buildVm(
            transactions = listOf(income(usd("100"), category = 1, date = LocalDate.of(2026, 7, 10))),
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals("100.00 USD", ready(vm.state.value).incomeDisplay)

        vm.selectPeriod(TrackingPeriod.DAY)
        advanceUntilIdle()
        assertEquals(TrackingWindow.Period(TrackingPeriod.DAY, julRef), vm.state.value.window)
        assertEquals("0.00 USD", ready(vm.state.value).incomeDisplay) // Jul 10 is outside the Jul 15 day
    }

    @Test
    fun inPeriodForeignExpense_withNoRate_yieldsMissingRate() = runTest {
        val vm = buildVm(
            transactions = listOf(expense(eur("30"), category = 7, date = LocalDate.of(2026, 7, 5))),
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(ReportsData.MissingRate("EUR"), vm.state.value.data)
    }

    @Test
    fun reportReResolvesLive_whenRateAddedWhileSubscribed() = runTest {
        // The Option-B guarantee: after the missing rate is set, the report recovers to numbers on the
        // SAME live subscription — no re-entry. (A UI-level .catch would have terminated the flow here.)
        val rateRepo = FakeRateRepository(emptyList())
        val vm = buildVm(
            transactions = listOf(expense(eur("30"), category = 7, date = LocalDate.of(2026, 7, 5))),
            categories = listOf(Category(id = 7, name = "Food", kind = CategoryKind.EXPENSE)),
            rateRepo = rateRepo,
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(ReportsData.MissingRate("EUR"), vm.state.value.data)

        rateRepo.upsert(ExchangeRate("EUR", BigDecimal("1.10"))) // 1 EUR = 1.10 USD
        advanceUntilIdle()

        val data = ready(vm.state.value)
        assertEquals("33.00 USD", data.expenseDisplay) // 30 EUR × 1.10
        assertEquals(CategoryLabel.Named("Food"), data.expenseCategories.single().label)
    }

    @Test
    fun emptyPeriod_isReadyWithZeros_notStuckLoading() = runTest {
        val vm = buildVm(transactions = emptyList())
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        val data = ready(vm.state.value)
        assertEquals("0.00 USD", data.incomeDisplay)
        assertEquals("0.00 USD", data.expenseDisplay)
        assertEquals("0.00 USD", data.netDisplay)
        assertEquals(emptyList<CategoryRow>(), data.expenseCategories)
        assertEquals(emptyList<CategoryRow>(), data.incomeCategories)
    }

    @Test
    fun subCategoriesBecomeRowsUnderTheirCategory_withTheRemainderLast() = runTest {
        val vm = buildVm(
            transactions = listOf(
                expense(usd("30"), category = 7, date = LocalDate.of(2026, 7, 5), sub = 71),
                expense(usd("5"), category = 7, date = LocalDate.of(2026, 7, 6)),
                expense(usd("9"), category = 9, date = LocalDate.of(2026, 7, 7)),
            ),
            categories = listOf(
                Category(id = 7, name = "Food", kind = CategoryKind.EXPENSE),
                Category(id = 71, name = "Groceries", kind = CategoryKind.EXPENSE, parentId = 7),
                Category(id = 9, name = "Transport", kind = CategoryKind.EXPENSE),
            ),
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        val (food, transport) = ready(vm.state.value).expenseCategories
        assertEquals(
            listOf(CategoryLabel.Named("Groceries"), CategoryLabel.NoSubCategory),
            food.subCategories.map { it.label },
        )
        assertEquals(listOf("30.00 USD", "5.00 USD"), food.subCategories.map { it.totalDisplay })
        assertEquals(true, food.canExpand)
        assertEquals(false, transport.canExpand) // only the remainder: nothing to open
    }

    @Test
    fun canStepIntoTheFuture() = runTest {
        val vm = buildVm(transactions = listOf(income(usd("40"), category = 1, date = LocalDate.of(2026, 8, 10))))
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.step(1)
        advanceUntilIdle()

        assertEquals(TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 8, 15)), vm.state.value.window)
        assertEquals("40.00 USD", ready(vm.state.value).incomeDisplay)
    }

    @Test
    fun pickingAMonthMovesTheAnchorAndArrowsStepFromIt() = runTest {
        val vm = buildVm(transactions = emptyList())
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.jumpTo(LocalDate.of(2025, 3, 3))
        vm.step(1)
        advanceUntilIdle()

        assertEquals(TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2025, 4, 3)), vm.state.value.window)
    }

    @Test
    fun aCustomRangeIsOrderedWhicheverDateWasPickedFirst() = runTest {
        val vm = buildVm(transactions = emptyList())
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.selectRange(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 3))
        advanceUntilIdle()

        assertEquals(
            TrackingWindow.Custom(DateRange(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 17))),
            vm.state.value.window,
        )
    }

    @Test
    fun clearingACustomRangeReturnsToTheCurrentMonth() = runTest {
        val vm = buildVm(transactions = emptyList())
        backgroundScope.launch { vm.state.collect {} }
        vm.selectRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 9))
        advanceUntilIdle()

        vm.clearRange()
        advanceUntilIdle()

        assertEquals(TrackingWindow.Period(TrackingPeriod.MONTH, julRef), vm.state.value.window)
    }

    @Test
    fun windowSurvivesRecreation() = runTest {
        val saved = SavedStateHandle()
        val first = buildVm(transactions = emptyList(), savedState = saved)
        backgroundScope.launch { first.state.collect {} }
        first.step(-1)
        first.selectPeriod(TrackingPeriod.YEAR)
        advanceUntilIdle()
        // June, then switched to its year: anchored on June 1 since today (Jul 15) was outside June.
        assertEquals(TrackingWindow.Period(TrackingPeriod.YEAR, LocalDate.of(2026, 6, 1)), first.state.value.window)

        val restored = buildVm(transactions = emptyList(), savedState = saved)
        backgroundScope.launch { restored.state.collect {} }
        advanceUntilIdle()

        assertEquals(first.state.value.window, restored.state.value.window)
    }

    @Test
    fun customWindowSurvivesRecreation() = runTest {
        val saved = SavedStateHandle()
        val first = buildVm(transactions = emptyList(), savedState = saved)
        backgroundScope.launch { first.state.collect {} }
        first.selectRange(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 9))
        advanceUntilIdle()

        val restored = buildVm(transactions = emptyList(), savedState = saved)
        backgroundScope.launch { restored.state.collect {} }
        advanceUntilIdle()

        assertEquals(first.state.value.window, restored.state.value.window)
    }

    @Test
    fun pickingADayFromACustomRangeOpensThatMonth() = runTest {
        val vm = buildVm(transactions = emptyList())
        backgroundScope.launch { vm.state.collect {} }
        vm.selectRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 9))
        advanceUntilIdle()

        vm.jumpTo(LocalDate.of(2026, 5, 20))
        advanceUntilIdle()

        assertEquals(TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 5, 20)), vm.state.value.window)
    }

    @Test
    fun theCurrentMonthFollowsTodayWhenTheScreenComesBack() = runTest {
        val vm = buildVm(transactions = listOf(income(usd("40"), category = 1, date = LocalDate.of(2026, 8, 3))))
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        now = LocalDate.of(2026, 8, 2)
        vm.onScreenStart()
        advanceUntilIdle()

        assertEquals(TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 8, 2)), vm.state.value.window)
        assertEquals("40.00 USD", ready(vm.state.value).incomeDisplay)
    }

    @Test
    fun oneWindowReadsItsTransactionsOnce() = runTest {
        val repo = FakeTransactionRepository(listOf(expense(usd("5"), category = 2, date = LocalDate.of(2026, 7, 3))))
        val vm = buildVm(transactions = emptyList(), txRepo = repo)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        // Totals and category lists come from the same read, so they can never disagree.
        assertEquals(1, repo.readsOpened)
        assertEquals("5.00 USD", ready(vm.state.value).expenseDisplay)
    }

    @Test
    fun computesOnTheWorkDispatcher() = runTest {
        val work = namedWorkThread(WORK_THREAD)
        try {
            // Converting and formatting both walk the currencies, so whoever walks them computed the report.
            val reportData = ThreadRecordingList(usdEur)
            val vm = buildVm(
                transactions = listOf(expense(usd("5"), category = 2, date = LocalDate.of(2026, 7, 3))),
                currencies = reportData,
                work = work.asCoroutineDispatcher(),
            )

            val ready = vm.state.first { it.data is ReportsData.Ready }

            assertEquals("5.00 USD", ready(ready).expenseDisplay)
            assertEquals(setOf(WORK_THREAD), reportData.threads)
        } finally {
            work.shutdown()
        }
    }
}

private const val WORK_THREAD = "work-test"

// --- Minimal in-memory ports (domain fakes live in :domain's test source set). ---

private class FakeTransactionRepository(initial: List<Transaction>) : TransactionRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Transaction>> = items
    override fun observeBetween(range: DateRange): Flow<List<Transaction>> =
        items.map { all -> all.filter { it.date in range } }.onStart { readsOpened++ }

    /** How many ranged reads were ever started; each one re-runs on every write. */
    var readsOpened = 0
        private set

    override suspend fun findById(id: Long): Transaction? = items.value.firstOrNull { it.id == id }
    override suspend fun add(transaction: Transaction): Long = 1
    override suspend fun update(transaction: Transaction) { items.value = items.value.map { if (it.id == transaction.id) transaction else it } }
    override suspend fun delete(id: Long) { items.value = items.value.filterNot { it.id == id } }
}

private class FakeCategoryRepository(initial: List<Category>) : CategoryRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Category>> = items
    override suspend fun findById(id: Long): Category? = items.value.firstOrNull { it.id == id }

    // Reports only read categories — managing them is a different screen. Failing here rather than
    // returning something plausible keeps a drifting test from passing quietly.
    override suspend fun upsert(category: Category): Long = error("reports do not write categories")

    override suspend fun deleteAndReassign(categoryId: Long, kind: CategoryKind, fallbackKey: String): Int =
        error("reports do not delete categories")
}

private class FakeCurrencyRepository(initial: List<Currency>) : CurrencyRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Currency>> = items
    override suspend fun findByCode(code: String): Currency? = items.value.firstOrNull { it.code == code }
}

private class FakeRateRepository(initial: List<ExchangeRate>) : RateRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<ExchangeRate>> = items
    override suspend fun findByCode(code: String): ExchangeRate? = items.value.firstOrNull { it.currencyCode == code }
    override suspend fun upsert(rate: ExchangeRate) {
        items.value = items.value.filterNot { it.currencyCode == rate.currencyCode } + rate
    }
}

private class FakeSettingsRepository(initial: Settings) : SettingsRepository {
    private val state = MutableStateFlow<Settings?>(initial)
    override fun observe(): Flow<Settings> = state.filterNotNull()
    override fun observeOrNull(): Flow<Settings?> = state
    override suspend fun get(): Settings = state.value ?: error("settings not initialized")
    override suspend fun save(settings: Settings) { state.value = settings }
}
