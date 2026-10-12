package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DeductionItem
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.SuggestCounterparties
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class IncomeDeductionsTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()
    private class Fixture(var failRules: Boolean = false, private val delayRules: Boolean = false) {
        val accounts = FakeAccountRepository(listOf(Account(1, "Bank", "cash", "USD", Money.zero("USD")),
            Account(2, "Euro", "cash", "EUR", Money.zero("EUR"))))
        val categories = FakeCategoryRepository(listOf(Category(1, "Salary", CategoryKind.INCOME),
            Category(9, "Costs", CategoryKind.EXPENSE)))
        val currencies = FakeCurrencyRepository(listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)))
        val rates = FakeRateRepository()
        val settings = FakeSettingsRepository(Settings(baseCurrencyCode = "USD"))
        val use = EntryUseCases(accounts, categories, FakeEntryRepository(), currencies, rates, settings)
        val reported = mutableListOf<Throwable>()
        val vm = AddTransactionViewModel(accounts, categories, use.saveEntry, TodayProvider { LocalDate.of(2026, 10, 12) },
            MoneyParser(Locale.US), FakeFxRepository(currencies, rates, settings), settings, MoneyFormatter(Locale.US),
            SuggestCounterparties(use.debts), deductionItems = object :
                org.aristonis.mywallet.domain.port.DeductionItemRepository by use.deductions {
                override fun observeAll() = kotlinx.coroutines.flow.flow<List<DeductionItem>> {
                    if (delayRules) kotlinx.coroutines.delay(1000)
                    if (failRules) throw IllegalStateException("private rule failure")
                    emitAll(use.deductions.observeAll())
                }
            },
            errors = org.aristonis.mywallet.di.ErrorReporter { _, failure -> reported.add(failure) })
        suspend fun seed() {
            use.deductions.upsert(item(1, "Tax", "10", DeductionTiming.LATER))
            use.deductions.upsert(item(2, "Family", "5", DeductionTiming.LATER))
            use.deductions.upsert(item(3, "Bills", "20", DeductionTiming.NOW, DeductionMode.FIXED))
        }
        fun fill() { vm.selectType(TransactionType.INCOME); vm.selectCategory(1); vm.setAmount("1000") }
        private fun item(id: Long, name: String, value: String, timing: DeductionTiming,
                         mode: DeductionMode = DeductionMode.PERCENT) = DeductionItem(id, name, mode,
            BigDecimal(value), if (mode == DeductionMode.FIXED) "USD" else null, timing, 9, setOf(1))
    }
    @Test fun `category picked before first item emission receives defaults once`() = runTest(dispatcher) {
        val f = Fixture(); f.seed(); f.fill(); advanceUntilIdle()
        assertEquals(3, f.vm.state.value.deductions.drafts.count { it.selected })
        f.vm.editDeduction(f.vm.state.value.deductions.drafts.first().itemId, DeductionEdit.SetSelected(false))
        f.use.deductions.upsert(f.use.deductions.findById(1)!!.copy(name = "Renamed"))
        advanceUntilIdle()
        assertFalse(f.vm.state.value.deductions.drafts.first { it.itemId == 1L }.selected)
    }
    @Test fun `rules read failure stays visible and blocks income after form mutations`() = runTest(dispatcher) {
        val f = Fixture(failRules = true); advanceUntilIdle(); f.fill()
        f.vm.setAmount("2000"); f.vm.setRate("USD", "2"); advanceUntilIdle()
        assertEquals(org.aristonis.mywallet.ui.message.UiMessage.Unexpected, f.vm.state.value.deductions.error)
        assertFalse(f.vm.state.value.canSubmit)
        f.vm.submit(); advanceUntilIdle()
        assertTrue(f.use.entries.stored.isEmpty())
    }
    @Test fun `reselecting the same category keeps this entry choices`() = runTest(dispatcher) {
        val f = Fixture(); f.seed(); advanceUntilIdle(); f.fill()
        f.vm.editDeduction(2L, DeductionEdit.SetSelected(false))
        f.vm.selectCategory(1)
        assertFalse(f.vm.state.value.deductions.drafts.first { it.itemId == 2L }.selected)
    }
    @Test fun `income waits for catalog then a retry preserves unticked choices`() = runTest(dispatcher) {
        val f = Fixture(delayRules = true); f.seed(); runCurrent(); f.fill()
        assertFalse(f.vm.state.value.canSubmit)
        advanceUntilIdle()
        assertTrue(f.vm.state.value.canSubmit)
        f.vm.editDeduction(2L, DeductionEdit.SetSelected(false))
        f.failRules = true; f.vm.retryDeductions(); advanceUntilIdle()
        assertFalse(f.vm.state.value.canSubmit)
        f.failRules = false; f.vm.retryDeductions(); runCurrent()
        assertFalse(f.vm.state.value.canSubmit)
        advanceUntilIdle()
        assertTrue(f.vm.state.value.canSubmit)
        assertFalse(f.vm.state.value.deductions.drafts.first { it.itemId == 2L }.selected)
        assertNull(f.vm.state.value.deductions.error)
    }
    @Test fun `first catalog failure can retry and apply selected category defaults`() = runTest(dispatcher) {
        val f = Fixture(failRules = true); f.seed(); f.fill(); advanceUntilIdle()
        f.failRules = false; f.vm.retryDeductions(); advanceUntilIdle()
        assertEquals(3, f.vm.state.value.deductions.drafts.count { it.selected })
        assertTrue(f.vm.state.value.canSubmit)
    }
    @Test fun `salary defaults preview and save exactly the same deductions`() = runTest(dispatcher) {
        val f = Fixture(); f.seed(); advanceUntilIdle(); f.fill()
        assertEquals(Money.of("980", "USD"), f.vm.state.value.deductions.summary!!.accountReceives)
        assertEquals(Money.of("150", "USD"), f.vm.state.value.deductions.summary!!.owed)
        f.vm.submit(); advanceUntilIdle()
        assertNull(f.vm.state.value.error)
        val income = f.use.entries.stored.single() as Transaction.Income
        assertEquals(listOf(Money.of("100", "USD"), Money.of("50", "USD"), Money.of("20", "USD")),
            income.deductions.map { it.amount })
        assertEquals(2, f.use.debts.observeAll().first().size)
    }
    @Test fun `unticking survives amount and item emissions without changing rule`() = runTest(dispatcher) {
        val f = Fixture(); f.seed(); advanceUntilIdle(); f.fill()
        f.vm.editDeduction(2L, DeductionEdit.SetSelected(false))
        f.vm.setAmount("2000")
        f.vm.setRate("USD", "3")
        f.rates.upsert(ExchangeRate("USD", BigDecimal("4")))
        f.use.deductions.upsert(f.use.deductions.findById(2)!!.copy(value = BigDecimal("7")))
        advanceUntilIdle()
        assertFalse(f.vm.state.value.deductions.drafts.first { it.itemId == 2L }.selected)
        assertEquals(BigDecimal("7"), f.use.deductions.findById(2)!!.value)
        f.vm.submit(); advanceUntilIdle()
        assertEquals(listOf(1L, 3L), (f.use.entries.stored.single() as Transaction.Income).deductions.map { it.itemId })
    }
    @Test fun `borrowing never offers or submits deductions`() = runTest(dispatcher) {
        val f = Fixture(); f.seed(); advanceUntilIdle(); f.fill(); f.vm.selectType(TransactionType.BORROW)
        assertTrue(f.vm.state.value.deductions.drafts.isEmpty())
        assertNull(f.vm.state.value.deductions.summary)
        f.vm.setCounterparty("Ali"); f.vm.submit(); advanceUntilIdle()
        assertNull(f.vm.state.value.error)
        assertTrue(f.use.entries.stored.single() is Transaction.Borrow)
    }
    @Test fun `full paid now deduction permits saving zero receipt`() = runTest(dispatcher) {
        val f = Fixture()
        f.use.deductions.upsert(DeductionItem(1, "All paid", DeductionMode.PERCENT, BigDecimal("100"),
            timing = DeductionTiming.NOW, expenseCategoryId = 9, autoAddCategoryIds = setOf(1)))
        advanceUntilIdle(); f.fill()
        assertTrue(f.vm.state.value.canSubmit)
        f.vm.submit(); advanceUntilIdle()
        assertNull(f.vm.state.value.error)
        assertEquals(Money.of("1000", "USD"),
            (f.use.entries.stored.single() as Transaction.Income).deductions.single().amount)
    }
    @Test fun `unexpected save failure is reported safely`() = runTest(dispatcher) {
        val f = Fixture(); advanceUntilIdle(); f.vm.selectType(TransactionType.INCOME)
        f.vm.selectCategory(1); f.vm.setAmount("1000")
        val failure = IllegalStateException("private failure")
        f.use.entries.failWith = failure
        f.vm.submit(); advanceUntilIdle()
        assertEquals(listOf(failure), f.reported)
        assertEquals(org.aristonis.mywallet.ui.message.UiMessage.Unexpected, f.vm.state.value.error)
    }
    @Test fun `cancellation never becomes a displayed save error`() = runTest(dispatcher) {
        val f = Fixture(); advanceUntilIdle(); f.vm.selectType(TransactionType.INCOME)
        f.vm.selectCategory(1); f.vm.setAmount("1000")
        f.use.entries.failWith = kotlinx.coroutines.CancellationException("cancel")
        f.vm.submit(); advanceUntilIdle()
        assertTrue(f.reported.isEmpty())
        assertNull(f.vm.state.value.error)
    }
    @Test fun `foreign rate field can price and save a deduction absent from main quote`() = runTest(dispatcher) {
        val f = Fixture()
        f.use.deductions.upsert(DeductionItem(4, "Foreign", DeductionMode.FIXED, BigDecimal("20"), "EUR",
            DeductionTiming.NOW, 9, setOf(1)))
        advanceUntilIdle(); f.fill()
        assertFalse(f.vm.state.value.canSubmit)
        assertEquals(listOf("EUR"), f.vm.state.value.rateFields.map { it.currencyCode })
        f.vm.setRate("EUR", "2")
        assertTrue(f.vm.state.value.canSubmit)
        f.vm.submit(); advanceUntilIdle()
        assertNull(f.vm.state.value.error)
        assertEquals(Money.of("40", "USD"),
            (f.use.entries.stored.single() as Transaction.Income).deductions.single().amount)
    }
    @Test fun `changing account currency discards a manually converted amount`() = runTest(dispatcher) {
        val f = Fixture()
        f.use.deductions.upsert(DeductionItem(4, "Foreign", DeductionMode.FIXED, BigDecimal("20"), "EUR",
            DeductionTiming.NOW, 9, setOf(1)))
        f.rates.upsert(ExchangeRate("EUR", BigDecimal("2")))
        advanceUntilIdle(); f.fill()
        f.vm.editDeduction(f.vm.state.value.deductions.drafts.single().itemId, DeductionEdit.SetConverted("35"))
        assertEquals(Money.of("35", "USD"), f.vm.state.value.deductions.summary!!.deductions)
        f.vm.selectAccount(2)
        assertEquals(Money.of("20", "EUR"), f.vm.state.value.deductions.summary!!.deductions)
        assertEquals("", f.vm.state.value.deductions.drafts.single().overrideInput)
    }
    @Test fun `invalid deduction disables submit and maps visible refusal`() = runTest(dispatcher) {
        val f = Fixture(); f.seed(); advanceUntilIdle(); f.fill()
        f.vm.editDeduction(f.vm.state.value.deductions.drafts.first().itemId, DeductionEdit.SetValue("120"))
        assertFalse(f.vm.state.value.canSubmit)
        assertEquals(org.aristonis.mywallet.ui.message.UiMessage.DeductionOutOfRange, f.vm.state.value.deductions.error)
    }
}
