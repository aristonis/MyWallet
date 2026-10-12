package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.toParts
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Locale

/**
 * A fixed deduction on an edited foreign income is priced at the rate that income was recorded
 * with, the same rate its rate field shows and the save stamps, not at today's saved rate. Pricing
 * the line at one rate while the books value it at another would understate the expense silently.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditDeductionKeptRateTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()

    /** Base USD; a EUR 1000 income recorded at 1.10 with a fixed 100 USD tax; today's EUR rate is 1.20. */
    private class Fixture(recorded: String = "90.91") {
        val tax = DeductionLine(1, 0, "Tax", DeductionMode.FIXED, BigDecimal("100"), "USD",
            DeductionTiming.NOW, 9, Money.of(recorded, "EUR"))
        val income = Transaction.Income(1, 2, Money.of("1000", "EUR"), 1, date = LocalDate.of(2026, 10, 10),
            rateToBase = BigDecimal("1.10"), deductions = listOf(tax))
        val accounts = FakeAccountRepository(listOf(Account(1, "Bank", "cash", "USD", Money.zero("USD")),
            Account(2, "Euro", "cash", "EUR", Money.zero("EUR"))))
        val categories = FakeCategoryRepository(listOf(Category(1, "Salary", CategoryKind.INCOME),
            Category(9, "Costs", CategoryKind.EXPENSE)))
        val currencies = FakeCurrencyRepository(listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)))
        val rates = FakeRateRepository(listOf(ExchangeRate("EUR", BigDecimal("1.20"))))
        val settings = FakeSettingsRepository(Settings(baseCurrencyCode = "USD"))
        val use = EntryUseCases(accounts, categories, FakeEntryRepository(listOf(income)), currencies, rates, settings)
        val vm = EditTransactionViewModel(accounts, categories, use.entries, use.saveEntry, use.deleteEntry,
            use.restoreEntries, MoneyParser(Locale.US), MoneyFormatter(Locale.US),
            FakeFxRepository(currencies, rates, settings), use.debts, SuggestCounterparties(use.debts),
            deductionItems = use.deductions)

        suspend fun seed() {
            use.deductions.upsert(DeductionItem(1, "Tax", DeductionMode.FIXED, BigDecimal("100"), "USD",
                DeductionTiming.NOW, 9))
        }

        fun stored() = use.entries.stored.single() as Transaction.Income
    }

    @Test fun `edited fixed deduction is priced at the entry kept rate and valued at it`() = runTest(dispatcher) {
        val f = Fixture(); f.seed(); f.vm.load(1); advanceUntilIdle()
        assertEquals("1.1", f.vm.state.value.rateFields.single { it.currencyCode == "EUR" }.input)

        f.vm.editDeduction(f.vm.state.value.deductions.drafts.single().itemId, DeductionEdit.SetValue("110"))
        assertEquals(Money.of("100.00", "EUR"), f.vm.state.value.deductions.lines.single().amount)
        f.vm.submit(); advanceUntilIdle()

        assertNull(f.vm.state.value.error)
        assertEquals(Money.of("100.00", "EUR"), f.stored().deductions.single().amount)
        val leg = f.stored().toParts().legs.single { it.role == LegRole.DEDUCTION }
        val baseValue = leg.amount.amount.multiply(checkNotNull(leg.rateToBase)).setScale(2, RoundingMode.HALF_UP)
        assertEquals(BigDecimal("110.00"), baseValue)
    }

    @Test fun `a cleared rate field keeps the unchanged line and does not block save`() = runTest(dispatcher) {
        val f = Fixture("90.00"); f.seed(); f.vm.load(1); advanceUntilIdle()
        f.vm.setRate("EUR", "")

        assertNull(f.vm.state.value.deductions.error)
        assertTrue(f.vm.state.value.canSubmit)
        f.vm.submit(); advanceUntilIdle()
        assertNull(f.vm.state.value.error)
        assertEquals(f.tax, f.stored().deductions.single())
    }

    @Test fun `retyping the prefilled rate keeps the unchanged line at its recorded amount`() = runTest(dispatcher) {
        val f = Fixture("90.00"); f.seed(); f.vm.load(1); advanceUntilIdle()
        f.vm.setRate("EUR", "1.10")

        assertEquals(Money.of("90.00", "EUR"), f.vm.state.value.deductions.lines.single().amount)
        assertTrue(f.vm.state.value.canSubmit)
        f.vm.submit(); advanceUntilIdle()
        assertNull(f.vm.state.value.error)
        assertEquals(f.tax, f.stored().deductions.single())
    }
}
