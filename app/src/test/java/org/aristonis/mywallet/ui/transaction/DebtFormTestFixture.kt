package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.SuggestCounterparties
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

internal class DebtFormTestFixture(
    initial: List<Transaction> = emptyList(),
    accounts: List<Account> = listOf(account(1, "USD"), account(2, "EUR"), account(3, "USD").copy(archived = true)),
) {
    val accountRepo = FakeAccountRepository(accounts)
    val categoryRepo = FakeCategoryRepository(listOf(
        Category(id = 5, name = "Salary", kind = CategoryKind.INCOME),
        Category(id = 6, name = "Food", kind = CategoryKind.EXPENSE)))
    val currencyRepo = FakeCurrencyRepository(listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2), Currency("SYP", "£S", 2)))
    val rateRepo = FakeRateRepository(listOf(ExchangeRate("USD", BigDecimal("13000")), ExchangeRate("EUR", BigDecimal("14000"))))
    val settingsRepo = FakeSettingsRepository(Settings(baseCurrencyCode = "SYP"))
    val debts = FakeDebtRepository(listOf(Debt(7, "Ali", DebtSide.I_OWE, "USD"), Debt(8, "Sara", DebtSide.OWED_TO_ME, "EUR")))
    val use = EntryUseCases(accountRepo, categoryRepo, FakeEntryRepository(initial), currencyRepo, rateRepo, settingsRepo, debts)
    private val fx = FakeFxRepository(currencyRepo, rateRepo, settingsRepo)
    val add = AddTransactionViewModel(accountRepo, categoryRepo, use.saveEntry, TodayProvider { day },
        MoneyParser(Locale.US), fx, settingsRepo, MoneyFormatter(Locale.US), SuggestCounterparties(debts))
    val edit = EditTransactionViewModel(accountRepo, categoryRepo, use.entries, use.saveEntry,
        use.deleteEntry, use.restoreEntries, MoneyParser(Locale.US), MoneyFormatter(Locale.US), fx,
        debts = debts, suggestCounterparties = SuggestCounterparties(debts))

    companion object {
        val day: LocalDate = LocalDate.of(2026, 10, 10)
        fun account(id: Long, code: String) = Account(id, "Cash $id", "cash", code, Money.zero(code))
        fun usd(amount: String) = Money.of(amount, "USD")
        fun eur(amount: String) = Money.of(amount, "EUR")
    }
}
