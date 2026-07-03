package org.aristonis.mywallet.ui.reports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.port.TransactionRepository
import org.aristonis.mywallet.domain.usecase.ComputeCategoryBreakdown
import org.aristonis.mywallet.domain.usecase.ComputePeriodSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val julRef = LocalDate.of(2026, 7, 15)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun usd(a: String) = Money.of(a, "USD")
    private fun eur(a: String) = Money.of(a, "EUR")

    private val usdEur = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2))

    private fun buildVm(
        transactions: List<Transaction>,
        categories: List<Category> = emptyList(),
        currencies: List<Currency> = usdEur,
        rateRepo: RateRepository = FakeRateRepository(emptyList()),
        base: String = "USD",
    ): ReportsViewModel {
        val txRepo = FakeTransactionRepository(transactions)
        val currencyRepo = FakeCurrencyRepository(currencies)
        val settingsRepo = FakeSettingsRepository(Settings(baseCurrencyCode = base))
        val categoryRepo = FakeCategoryRepository(categories)
        return ReportsViewModel(
            computePeriodSummary = ComputePeriodSummary(txRepo, currencyRepo, rateRepo, settingsRepo),
            computeCategoryBreakdown = ComputeCategoryBreakdown(txRepo, currencyRepo, rateRepo, settingsRepo),
            categories = categoryRepo,
            today = TodayProvider { julRef },
        )
    }

    private fun income(amount: Money, category: Long, date: LocalDate) =
        Transaction.Income(id = 0, accountId = 1, amount = amount, categoryId = category, date = date)

    private fun expense(amount: Money, category: Long, date: LocalDate) =
        Transaction.Expense(id = 0, accountId = 1, amount = amount, categoryId = category, date = date)

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
        assertEquals(TrackingPeriod.MONTH, state.selectedPeriod)
        val data = ready(state)
        assertEquals(usd("100"), data.income)
        assertEquals(usd("30"), data.expense)
        assertEquals(usd("70"), data.net)
    }

    @Test
    fun categoryBreakdown_resolvesNames_expenseOnly_sortedByTotalDesc() = runTest {
        val vm = buildVm(
            transactions = listOf(
                expense(usd("15"), category = 9, date = LocalDate.of(2026, 7, 5)),
                expense(usd("30"), category = 7, date = LocalDate.of(2026, 7, 6)),
                expense(usd("20"), category = 7, date = LocalDate.of(2026, 7, 7)),
                income(usd("500"), category = 1, date = LocalDate.of(2026, 7, 8)), // ignored
            ),
            categories = listOf(
                Category(id = 7, name = "Food", kind = CategoryKind.EXPENSE),
                Category(id = 9, name = "Transport", kind = CategoryKind.EXPENSE),
            ),
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        val rows = ready(vm.state.value).categories
        assertEquals(listOf("Food", "Transport"), rows.map { it.name }) // 50 before 15 (desc)
        assertEquals(usd("50"), rows[0].total)
        assertEquals(usd("15"), rows[1].total)
    }

    @Test
    fun unknownCategoryId_degradesToDash() = runTest {
        val vm = buildVm(
            transactions = listOf(expense(usd("10"), category = 99, date = LocalDate.of(2026, 7, 5))),
            categories = emptyList(),
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals("—", ready(vm.state.value).categories.single().name)
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

        val rows = ready(vm.state.value).categories
        assertEquals(listOf("—", "—"), rows.map { it.name })
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
        assertEquals(usd("100"), ready(vm.state.value).income)

        vm.selectPeriod(TrackingPeriod.DAY)
        advanceUntilIdle()
        assertEquals(TrackingPeriod.DAY, vm.state.value.selectedPeriod)
        assertEquals(usd("0"), ready(vm.state.value).income) // Jul 10 is outside the Jul 15 day
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
        assertEquals(usd("33.00"), data.expense) // 30 EUR × 1.10
        assertEquals("Food", data.categories.single().name)
    }

    @Test
    fun emptyPeriod_isReadyWithZeros_notStuckLoading() = runTest {
        val vm = buildVm(transactions = emptyList())
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        val data = ready(vm.state.value)
        assertEquals(usd("0"), data.income)
        assertEquals(usd("0"), data.expense)
        assertEquals(usd("0"), data.net)
        assertEquals(emptyList<CategoryRow>(), data.categories)
    }
}

// --- Minimal in-memory ports (domain fakes live in :domain's test source set). ---

private class FakeTransactionRepository(initial: List<Transaction>) : TransactionRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Transaction>> = items
    override suspend fun add(transaction: Transaction): Long = 1
}

private class FakeCategoryRepository(initial: List<Category>) : CategoryRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Category>> = items
    override suspend fun findById(id: Long): Category? = items.value.firstOrNull { it.id == id }
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
