package org.aristonis.mywallet.ui.transaction

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.aristonis.mywallet.RecordingErrorReporter
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.model.*
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.ui.HealthLedger
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsListFilterTest {
    private val dispatcher = StandardTestDispatcher()
    private val date = LocalDate.of(2026, 10, 17)
    private val cash = Account(1, "Cash", "cash", "USD", Money.zero("USD"))
    private val food = Category(20, "Food", CategoryKind.EXPENSE)
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()
    private fun expense(id: Long, account: Long = 1) = Transaction.Expense(
        id = id, accountId = account, amount = Money.of("5", "USD"), categoryId = 20, date = date,
    )
    private fun income(id: Long) = Transaction.Income(
        id = id, accountId = 1, amount = Money.of("10", "USD"), categoryId = 21, date = date,
    )
    private fun TestScope.vm(entries: EntryRepository, handle: SavedStateHandle = SavedStateHandle()) =
        TransactionsListViewModel(entries, FakeAccountRepository(listOf(cash)), FakeCategoryRepository(listOf(food)),
            FakeCurrencyRepository(listOf(Currency("USD", "$", 2))), FakeSettingsRepository(Settings("USD")),
            FakeDebtRepository(), HealthLedger().health(), MoneyFormatter(Locale.US), { date }, handle,
            dispatcher, RecordingErrorReporter()).also { watch(it) }

    @Test fun `type changes restart paging and keep only matching entries`() = runTest {
        val entries = FakeEntryRepository((1L..220).map(::expense) + income(221))
        val vm = vm(entries)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(200, vm.state.value.rowCount)
        vm.toggleType(TypeGroup.INCOME)
        advanceUntilIdle()
        assertEquals(listOf(221L), vm.state.value.rows.map { it.id })
        vm.toggleType(TypeGroup.ALL)
        advanceUntilIdle()
        assertEquals(100, vm.state.value.rowCount)
    }

    @Test fun `filter remains in same handle and fresh VM starts at All`() = runTest {
        val handle = SavedStateHandle()
        val vm = vm(FakeEntryRepository(), handle)
        vm.toggleType(TypeGroup.TRANSFER)
        vm.toggleAccount(1)
        vm.toggleCategory(20)
        advanceUntilIdle()
        vm.onScreenStart()
        advanceUntilIdle()
        assertEquals(TransactionFilter(setOf(TypeGroup.TRANSFER), setOf(1), setOf(20)), vm.state.value.filter)
        val recreated = vm(FakeEntryRepository(), handle)
        val fresh = vm(FakeEntryRepository())
        advanceUntilIdle()
        assertEquals(vm.state.value.filter, recreated.state.value.filter)
        assertEquals(TransactionFilter(), fresh.state.value.filter)
    }

    @Test fun `saved outside any filter offers Show which clears all terms`() = runTest {
        val entries = FakeEntryRepository(listOf(expense(4, 2)))
        val vm = vm(entries)
        vm.toggleType(TypeGroup.TRANSFER)
        vm.toggleAccount(1)
        vm.toggleCategory(20)
        advanceUntilIdle()
        vm.onEntrySaved(4, date)
        advanceUntilIdle()
        assertEquals(date, vm.state.value.savedOutsideWindow)
        vm.showSavedEntry()
        advanceUntilIdle()
        assertEquals(TransactionFilter(), vm.state.value.filter)
        assertEquals(listOf(4L), vm.state.value.rows.map { it.id })
        assertNull(vm.state.value.savedOutsideWindow)
    }

    @Test fun `clear all resets every term and restores the list`() = runTest {
        val vm = vm(FakeEntryRepository(listOf(expense(1))))
        vm.toggleType(TypeGroup.INCOME)
        vm.toggleAccount(99)
        vm.toggleCategory(99)
        advanceUntilIdle()
        assertTrue(vm.state.value.rows.isEmpty())
        vm.clearFilters()
        advanceUntilIdle()
        assertEquals(TransactionFilter(), vm.state.value.filter)
        assertEquals(listOf(1L), vm.state.value.rows.map { it.id })
    }
    @Test fun `late saved lookup cannot restore a dismissed message after filter changes`() = runTest {
        val answer = CompletableDeferred<Transaction?>()
        val base = FakeEntryRepository(listOf(expense(4)))
        val entries = object : EntryRepository by base {
            override suspend fun findById(id: Long): Transaction? = answer.await()
        }
        val vm = vm(entries)
        vm.toggleType(TypeGroup.TRANSFER)
        advanceUntilIdle()
        vm.onEntrySaved(4, date)
        runCurrent()
        vm.toggleType(TypeGroup.ALL)
        answer.complete(expense(4))
        advanceUntilIdle()
        assertNull(vm.state.value.savedOutsideWindow)
        assertEquals(emptySet<TypeGroup>(), vm.state.value.filter.types)
    }

    @Test fun `missing saved entry and failed lookup do not crash or invent visibility`() = runTest {
        val base = FakeEntryRepository()
        val entries = object : EntryRepository by base {
            override suspend fun findById(id: Long): Transaction? = error("unavailable")
        }
        val vm = vm(entries)
        vm.toggleType(TypeGroup.INCOME)
        advanceUntilIdle()
        vm.onEntrySaved(4, date)
        advanceUntilIdle()
        assertNull(vm.state.value.savedOutsideWindow)
        assertFalse(vm.state.value.loadFailed)
        assertNotNull(vm.state.value.savedEntryFailure)
    }

    @Test fun `account category and type all combine and each selection resets paging`() = runTest {
        val entries = FakeEntryRepository((1L..220).map(::expense) + income(221) + expense(222, 2))
        val vm = vm(entries)
        advanceUntilIdle()
        vm.toggleType(TypeGroup.EXPENSE)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(200, vm.state.value.rowCount)
        vm.toggleAccount(1)
        advanceUntilIdle()
        assertEquals(100, vm.state.value.rowCount)
        assertFalse(vm.state.value.rows.any { it.id == 222L })
        vm.loadMore()
        advanceUntilIdle()
        vm.toggleCategory(20)
        advanceUntilIdle()
        assertEquals(100, vm.state.value.rowCount)
        vm.toggleCategory(20)
        vm.toggleCategory(21)
        advanceUntilIdle()
        assertTrue(vm.state.value.rows.isEmpty())
    }

    @Test fun `category matched through deduction shows its exact amount rather than salary`() = runTest {
        val tax = Category(30, "Tax", CategoryKind.EXPENSE)
        val salary = income(7).copy(amount = Money.of("1000", "USD"), deductions = listOf(
            DeductionLine(1, 0, "Tax", DeductionMode.FIXED, java.math.BigDecimal("100"), "USD",
                DeductionTiming.NOW, 30, Money.of("100", "USD")),
        ))
        val row = salary.toRow(RowLookups(mapOf(1L to cash), mapOf(30L to tax), listOf(Currency("USD", "$", 2)), "USD"), MoneyFormatter(Locale.US))
            .withMatchedParts(salary, setOf(30), RowLookups(mapOf(1L to cash), mapOf(30L to tax), listOf(Currency("USD", "$", 2)), "USD"), MoneyFormatter(Locale.US))
        assertEquals(Money.of("100", "USD"), row.matchedParts.single().amount)
        assertEquals("100.00 USD", row.matchedParts.single().amountDisplay)
        assertEquals(org.aristonis.mywallet.ui.CategoryLabel.Named("Tax"), row.matchedParts.single().category)
        assertEquals(Money.of("1000", "USD"), row.amount)
    }

    @Test fun `main category match leaves ordinary row unchanged and fee match is exact`() = runTest {
        val fees = Category(30, "Fees", CategoryKind.EXPENSE)
        val saved = expense(9).copy(fee = Money.of("0.25", "USD"), feeCategoryId = 30)
        val lookup = RowLookups(mapOf(1L to cash), mapOf(20L to food, 30L to fees), listOf(Currency("USD", "$", 2)), "USD")
        val formatter = MoneyFormatter(Locale.US)
        val row = saved.toRow(lookup, formatter)
        assertTrue(row.withMatchedParts(saved, emptySet(), lookup, formatter).matchedParts.isEmpty())
        assertTrue(row.withMatchedParts(saved, setOf(20), lookup, formatter).matchedParts.isEmpty())
        assertTrue(row.withMatchedParts(saved, setOf(99), lookup, formatter).matchedParts.isEmpty())
        assertEquals(Money.of("0.25", "USD"), row.withMatchedParts(saved, setOf(30), lookup, formatter).matchedParts.single().amount)
    }

    @Test fun `cancelling visibility lookup does not report a failure`() = runTest {
        val answer = CompletableDeferred<Transaction?>()
        val base = FakeEntryRepository()
        val entries = object : EntryRepository by base {
            override suspend fun findById(id: Long): Transaction? = answer.await()
        }
        val vm = vm(entries)
        vm.onEntrySaved(4, date)
        runCurrent()
        vm.dismissSavedEntryMessage()
        advanceUntilIdle()
        assertNull(vm.state.value.savedEntryFailure)
        assertNull(vm.state.value.savedOutsideWindow)
    }

    @Test fun `multiple choices OR inside groups and AND across groups`() = runTest {
        val vm = vm(FakeEntryRepository(listOf(expense(1), expense(2, 2), expense(3, 3), income(4))))
        vm.toggleType(TypeGroup.INCOME)
        vm.toggleType(TypeGroup.EXPENSE)
        vm.toggleAccount(1)
        vm.toggleAccount(2)
        vm.toggleCategory(20)
        vm.toggleCategory(21)
        advanceUntilIdle()
        assertEquals(listOf(4L, 2L, 1L), vm.state.value.rows.map { it.id })
        assertEquals(2, vm.state.value.filter.activeSheetCount)
        vm.toggleType(TypeGroup.EXPENSE)
        advanceUntilIdle()
        assertEquals(listOf(4L), vm.state.value.rows.map { it.id })
        vm.toggleType(TypeGroup.ALL)
        advanceUntilIdle()
        assertEquals(listOf(4L, 2L, 1L), vm.state.value.rows.map { it.id })
    }

    @Test fun `parent and child selection explain a deduction once`() = runTest {
        val tax = Category(30, "Tax", CategoryKind.EXPENSE)
        val regional = Category(31, "Regional", CategoryKind.EXPENSE, parentId = 30)
        val salary = income(7).copy(deductions = listOf(
            DeductionLine(1, 0, "Tax", DeductionMode.FIXED, java.math.BigDecimal("1"), "USD",
                DeductionTiming.NOW, 30, Money.of("1", "USD"), subCategoryId = 31),
        ))
        val lookup = RowLookups(mapOf(1L to cash), mapOf(30L to tax, 31L to regional), listOf(Currency("USD", "$", 2)), "USD")
        val formatter = MoneyFormatter(Locale.US)
        val row = salary.toRow(lookup, formatter).withMatchedParts(salary, setOf(30, 31), lookup, formatter)
        assertEquals(1, row.matchedParts.size)
        assertEquals(Money.of("1", "USD"), row.matchedParts.single().amount)
    }

    @Test fun `saved entry matching second account and category stays inside combined selection`() = runTest {
        val saved = expense(4, 2)
        val vm = vm(FakeEntryRepository(listOf(saved)))
        vm.toggleType(TypeGroup.INCOME)
        vm.toggleType(TypeGroup.EXPENSE)
        vm.toggleAccount(1)
        vm.toggleAccount(2)
        vm.toggleCategory(21)
        vm.toggleCategory(20)
        advanceUntilIdle()
        vm.onEntrySaved(4, date)
        advanceUntilIdle()
        assertNull(vm.state.value.savedOutsideWindow)
        assertEquals(listOf(4L), vm.state.value.rows.map { it.id })
        vm.toggleAccount(2)
        vm.onEntrySaved(4, date)
        advanceUntilIdle()
        assertEquals(date, vm.state.value.savedOutsideWindow)
        vm.showSavedEntry()
        advanceUntilIdle()
        assertEquals(TransactionFilter(), vm.state.value.filter)
        assertEquals(listOf(4L), vm.state.value.rows.map { it.id })
    }

}
