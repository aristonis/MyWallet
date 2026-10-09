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
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/**
 * The add and edit forms share one pricing path: the same account, amount and typed rate give the
 * same rate fields and the same base-currency figure on both, so the two screens cannot disagree
 * about what an entry is worth.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryFormsSameQuoteTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val food = Category(id = 20, name = "Food", kind = CategoryKind.EXPENSE)
    private val stored = Transaction.Expense(
        id = 5, accountId = 1, amount = Money.of("50", "USD"), categoryId = food.id,
        date = LocalDate.of(2026, 6, 1), rateToBase = BigDecimal("1.3"),
    )

    private inner class Forms(saveRates: Boolean) {
        private val accountRepo = FakeAccountRepository(
            listOf("USD", "SYP").mapIndexed { i, code ->
                Account(
                    id = i + 1L, name = "acct-$code", typeKey = "cash", currencyCode = code,
                    openingBalance = Money.of("1000", code),
                )
            },
        )
        private val categoryRepo = FakeCategoryRepository(listOf(food))
        private val currencyRepo = FakeCurrencyRepository(
            listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2), Currency("SYP", "£S", 2)),
        )
        private val rateRepo = FakeRateRepository(
            listOf(ExchangeRate("USD", BigDecimal("1.1")), ExchangeRate("SYP", BigDecimal("0.0001"))),
        )
        private val settingsRepo = FakeSettingsRepository(
            Settings(baseCurrencyCode = "EUR", saveTransactionRates = saveRates),
        )
        private val use = EntryUseCases(accountRepo, categoryRepo, FakeEntryRepository(listOf(stored)), currencyRepo, rateRepo, settingsRepo)
        private val fx = FakeFxRepository(currencyRepo, rateRepo, settingsRepo)

        val add = AddTransactionViewModel(
            accounts = accountRepo,
            categories = categoryRepo,
            saveEntry = use.saveEntry,
            today = TodayProvider { LocalDate.of(2026, 10, 8) },
            moneyParser = MoneyParser(Locale.US),
            fx = fx,
            settings = settingsRepo,
            moneyFormatter = MoneyFormatter(Locale.US),
        )
        val edit = EditTransactionViewModel(
            accounts = accountRepo,
            categories = categoryRepo,
            entries = use.entries,
            saveEntry = use.saveEntry,
            deleteEntry = use.deleteEntry,
            restoreEntries = use.restoreEntries,
            moneyParser = MoneyParser(Locale.US),
            moneyFormatter = MoneyFormatter(Locale.US),
            fx = fx,
            settings = settingsRepo,
        )
    }

    private fun Forms.fillBoth(typedRate: String?) {
        add.selectType(TransactionType.EXPENSE)
        add.selectAccount(2)
        add.selectCategory(food.id)
        add.setAmount("5000")
        edit.selectAccount(2)
        edit.setAmount("5000")
        if (typedRate != null) {
            add.setRate("SYP", typedRate)
            edit.setRate("SYP", typedRate)
        }
    }

    @Test
    fun addAndEditPriceTheSameInputsToTheSameQuote() = runTest {
        val forms = Forms(saveRates = true)
        forms.edit.load(5)
        advanceUntilIdle()

        forms.fillBoth(typedRate = "0.0002")

        assertEquals(forms.add.state.value.rateFields, forms.edit.state.value.rateFields)
        assertEquals(forms.add.state.value.baseEquivalent, forms.edit.state.value.baseEquivalent)
        assertNotNull(forms.edit.state.value.baseEquivalent)
        assertEquals("0.0002", forms.edit.state.value.rateFields.single().input)
    }

    @Test
    fun addAndEditAgreeOnTheSavedRateWhenNothingIsTyped() = runTest {
        val forms = Forms(saveRates = true)
        forms.edit.load(5)
        advanceUntilIdle()

        forms.fillBoth(typedRate = null)

        assertEquals(forms.add.state.value.rateFields, forms.edit.state.value.rateFields)
        assertEquals(forms.add.state.value.baseEquivalent, forms.edit.state.value.baseEquivalent)
        assertEquals("0.0001", forms.edit.state.value.rateFields.single().input)
    }

    @Test
    fun addAndEditBothLetARateBeTypedForOneEntryWhenSavingRatesIsOff() = runTest {
        val forms = Forms(saveRates = false)
        forms.edit.load(5)
        advanceUntilIdle()

        forms.fillBoth(typedRate = "0.0002")

        assertEquals(forms.add.state.value.rateFields, forms.edit.state.value.rateFields)
        assertTrue(forms.edit.state.value.rateFields.all { it.isEditable })
        assertEquals("0.0002", forms.edit.state.value.rateFields.single().input)
    }
}
