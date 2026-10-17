package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.domain.model.FeeRule
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.usecase.SuggestCounterparties
import org.junit.Assert.assertEquals
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/**
 * Add and edit forms over one set of fakes for the entry fee tests. Base USD. Accounts: 1 Wallet USD
 * with a 2% default fee, 2 Cash USD, 3 Euro EUR, 4 Savings USD, 5 Euro savings EUR; none of 2-5
 * has a default fee. [categoryFlow] can be changed to simulate a category removed elsewhere.
 */
internal class EntryFeeFixture(
    date: LocalDate,
    initial: List<Transaction> = emptyList(),
    missingRole: Boolean = false,
) {
    val accounts = FakeAccountRepository(listOf(
        Account(1, "Wallet", "cash", "USD", Money.zero("USD"),
            defaultFee = FeeRule(FeeMode.PERCENT, BigDecimal("2"))),
        Account(2, "Cash", "cash", "USD", Money.zero("USD")),
        Account(3, "Euro", "cash", "EUR", Money.zero("EUR")),
        Account(4, "Savings", "cash", "USD", Money.zero("USD")),
        Account(5, "Euro savings", "cash", "EUR", Money.zero("EUR"))))
    val categoryList = listOf(Category(3, "Salary", CategoryKind.INCOME),
        Category(4, "Food", CategoryKind.EXPENSE), Category(5, "Fees", CategoryKind.EXPENSE),
        Category(99, "Uncategorized", CategoryKind.EXPENSE),
        Category(6, "Snacks", CategoryKind.EXPENSE, parentId = 4))
    val categoryFlow = MutableStateFlow(categoryList)
    val categories = object : CategoryRepository by FakeCategoryRepository(categoryList) {
        override fun observeAll(): Flow<List<Category>> = categoryFlow
        override suspend fun bucketFor(kind: CategoryKind): Long {
            assertEquals(CategoryKind.EXPENSE, kind)
            return 99
        }
    }
    val currencies = FakeCurrencyRepository(listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)))
    val rates = FakeRateRepository()
    val settings = FakeSettingsRepository(Settings(baseCurrencyCode = "USD"))
    val roles = FakeFormRoles(if (missingRole) null else 5)
    val use = EntryUseCases(accounts, categories, FakeEntryRepository(initial), currencies, rates, settings,
        roles = roles)
    val add = AddTransactionViewModel(accounts, categories, use.saveEntry, TodayProvider { date },
        MoneyParser(Locale.US), FakeFxRepository(currencies, rates, settings), settings, MoneyFormatter(Locale.US),
        SuggestCounterparties(use.debts), use.deductions, roles, debts = use.debts)
    val edit = EditTransactionViewModel(accounts, categories, use.entries, use.saveEntry, use.deleteEntry,
        use.restoreEntries, MoneyParser(Locale.US), MoneyFormatter(Locale.US),
        FakeFxRepository(currencies, rates, settings), use.debts, SuggestCounterparties(use.debts),
        use.deductions, roles)

    /** A 300 transfer from the selected source into Cash (account 2). */
    fun transfer() {
        add.selectType(TransactionType.TRANSFER)
        add.selectDestAccount(2)
        add.setAmount("300")
    }
}
