package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DeductionItem
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.SuggestCounterparties
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class EditIncomeDeductionsTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()
    private class Fixture(mode: DeductionMode = DeductionMode.FIXED, amount: String = "31", gross: String = "1000", var failRules: Boolean = false) {
        val old = DeductionLine(1, 0, "Historical", mode, BigDecimal(if (mode == DeductionMode.FIXED) "20" else "10"),
            if (mode == DeductionMode.FIXED) "EUR" else null, DeductionTiming.NOW, 9, Money.of(amount, "USD"))
        val income = Transaction.Income(1, 1, Money.of(gross, "USD"), 1, date = LocalDate.of(2026, 10, 10),
            deductions = listOf(old))
        val accounts = FakeAccountRepository(listOf(Account(1, "Bank", "cash", "USD", Money.zero("USD")),
            Account(2, "Euro", "cash", "EUR", Money.zero("EUR"))))
        val categories = FakeCategoryRepository(listOf(Category(1, "Salary", CategoryKind.INCOME),
            Category(9, "Costs", CategoryKind.EXPENSE)))
        val currencies = FakeCurrencyRepository(listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)))
        val rates = FakeRateRepository(listOf(ExchangeRate("EUR", BigDecimal("2"))))
        val settings = FakeSettingsRepository(Settings(baseCurrencyCode = "USD"))
        val use = EntryUseCases(accounts, categories, FakeEntryRepository(listOf(income)), currencies, rates, settings)
        val vm = EditTransactionViewModel(accounts, categories, use.entries, use.saveEntry, use.deleteEntry,
            use.restoreEntries, MoneyParser(Locale.US), MoneyFormatter(Locale.US),
            FakeFxRepository(currencies, rates, settings), use.debts, SuggestCounterparties(use.debts),
            deductionItems = object :
                org.aristonis.mywallet.domain.port.DeductionItemRepository by use.deductions {
                override fun observeAll() = kotlinx.coroutines.flow.flow<List<DeductionItem>> {
                    if (failRules) throw IllegalStateException("private read error")
                    emitAll(use.deductions.observeAll())
                }
            })
        suspend fun seed() {
            use.deductions.upsert(DeductionItem(1, "Changed", DeductionMode.PERCENT, BigDecimal("80"),
                timing = DeductionTiming.LATER, expenseCategoryId = 9, enabled = false))
        }
    }
    @Test fun `edit rule catalog failure persists across gross edit and blocks save`() = runTest(dispatcher) {
        val f = Fixture(failRules = true); f.seed(); f.vm.load(1); advanceUntilIdle()
        f.vm.setAmount("2000")
        assertEquals(org.aristonis.mywallet.ui.message.UiMessage.Unexpected, f.vm.state.value.deductions.error)
        org.junit.Assert.assertFalse(f.vm.state.value.canSubmit)
        f.vm.submit(); advanceUntilIdle()
        assertEquals(f.income, f.use.entries.stored.single())
    }
    @Test fun `retrying failed catalog preserves recorded snapshot and entry choices`() = runTest(dispatcher) {
        val f = Fixture(failRules = true); f.seed(); f.vm.load(1); advanceUntilIdle()
        f.failRules = false; f.vm.retryDeductions(); advanceUntilIdle()
        org.junit.Assert.assertTrue(f.vm.state.value.canSubmit)
        assertEquals("Historical", f.vm.state.value.deductions.drafts.single().name)
        f.vm.editDeduction(f.vm.state.value.deductions.drafts.single().itemId, DeductionEdit.SetSelected(false))
        f.failRules = true; f.vm.retryDeductions(); advanceUntilIdle()
        f.failRules = false; f.vm.retryDeductions(); advanceUntilIdle()
        org.junit.Assert.assertFalse(f.vm.state.value.deductions.drafts.single().selected)
    }
    @Test fun `explicit fixed value edit back to original keeps repricing intent`() = runTest(dispatcher) {
        val f = Fixture(); f.seed(); f.vm.load(1); advanceUntilIdle()
        f.vm.editDeduction(f.vm.state.value.deductions.drafts.single().itemId, DeductionEdit.SetValue("21"))
        assertEquals(Money.of("42", "USD"), f.vm.state.value.deductions.summary!!.deductions)
        f.vm.editDeduction(f.vm.state.value.deductions.drafts.single().itemId, DeductionEdit.SetValue("20"))
        assertEquals(Money.of("40", "USD"), f.vm.state.value.deductions.summary!!.deductions)
        f.vm.editDeduction(f.vm.state.value.deductions.drafts.single().itemId, DeductionEdit.SetConverted("35"))
        assertEquals(Money.of("35", "USD"), f.vm.state.value.deductions.summary!!.deductions)
        f.vm.editDeduction(f.vm.state.value.deductions.drafts.single().itemId, DeductionEdit.SetConverted(""))
        assertEquals(Money.of("40", "USD"), f.vm.state.value.deductions.summary!!.deductions)
    }
    @Test fun `disabled renamed item preserves historical fixed value on no op and gross edit`() = runTest(dispatcher) {
        val f = Fixture(); f.seed(); f.vm.load(1); advanceUntilIdle()
        assertEquals("Historical", f.vm.state.value.deductions.drafts.single().name)
        assertEquals(Money.of("31", "USD"), f.vm.state.value.deductions.summary!!.deductions)
        f.vm.setAmount("2000"); f.vm.submit(); advanceUntilIdle()
        assertNull(f.vm.state.value.error)
        assertEquals(f.old, (f.use.entries.stored.single() as Transaction.Income).deductions.single())
    }
    @Test fun `explicit foreign rate reprices historical fixed deduction`() = runTest(dispatcher) {
        val f = Fixture(); f.seed(); f.vm.load(1); advanceUntilIdle()
        f.vm.setRate("EUR", "3")
        assertEquals(Money.of("60", "USD"), f.vm.state.value.deductions.summary!!.deductions)
        f.vm.submit(); advanceUntilIdle()
        assertNull(f.vm.state.value.error)
        assertEquals(Money.of("60", "USD"), (f.use.entries.stored.single() as Transaction.Income).deductions.single().amount)
    }
    @Test fun `percentage snapshot recalculates gross regardless of disabled live defaults`() = runTest(dispatcher) {
        val f = Fixture(DeductionMode.PERCENT, "100"); f.seed(); f.vm.load(1); advanceUntilIdle()
        f.vm.setAmount("2000"); f.vm.submit(); advanceUntilIdle()
        assertNull(f.vm.state.value.error)
        assertEquals(Money.of("200", "USD"), (f.use.entries.stored.single() as Transaction.Income).deductions.single().amount)
    }
    @Test fun `zero rounded snapshot remains selectable and savable`() = runTest(dispatcher) {
        val f = Fixture(DeductionMode.PERCENT, "0", "0.01"); f.seed(); f.vm.load(1); advanceUntilIdle()
        f.vm.submit(); advanceUntilIdle()
        assertNull(f.vm.state.value.error)
        assertEquals(f.old, (f.use.entries.stored.single() as Transaction.Income).deductions.single())
    }
    @Test fun `new account currency recomputes fixed amount`() = runTest(dispatcher) {
        val f = Fixture(); f.seed(); f.vm.load(1); advanceUntilIdle()
        f.vm.selectAccount(2)
        assertEquals(Money.of("20", "EUR"), f.vm.state.value.deductions.summary!!.deductions)
        f.vm.submit(); advanceUntilIdle()
        assertNull(f.vm.state.value.error)
        assertEquals(Money.of("20", "EUR"), (f.use.entries.stored.single() as Transaction.Income).deductions.single().amount)
    }
}
