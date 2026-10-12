package org.aristonis.mywallet.domain.usecase.fake

import kotlinx.coroutines.flow.first
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.CategoryRole
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.TransactionRunner
import org.aristonis.mywallet.domain.service.RoleCategories
import org.aristonis.mywallet.domain.usecase.DeleteEntry
import org.aristonis.mywallet.domain.usecase.RestoreEntries
import org.aristonis.mywallet.domain.usecase.SaveEntry
import org.aristonis.mywallet.domain.usecase.SetExchangeRate
import java.math.BigDecimal
import java.time.LocalDate

/** The fault [FaultyEntryRepository] raises, so a test can tell it from a real rule failing. */
class InjectedFault : RuntimeException("injected fault")

/**
 * Wraps a [FakeEntryRepository] and raises [InjectedFault] right AFTER a write has landed, which is
 * the worst moment: the header and legs are stored, and only the surrounding transaction can undo them.
 */
class FaultyEntryRepository(
    private val delegate: FakeEntryRepository,
    private val failAfterWrite: Boolean,
) : EntryRepository by delegate {

    override suspend fun insert(entry: Transaction): Long {
        val id = delegate.insert(entry)
        if (failAfterWrite) throw InjectedFault()
        return id
    }

    override suspend fun replace(entry: Transaction) {
        delegate.replace(entry)
        if (failAfterWrite) throw InjectedFault()
    }
}

/**
 * Wraps a [FakeLedgerQueries] and raises [InjectedFault] right AFTER the rate has been given to the
 * older legs, which is the last write of a save: entry, saved rate and filled legs are all stored.
 */
class FaultyLedgerQueries(
    private val delegate: LedgerQueries,
    private val failAfterFill: Boolean,
) : LedgerQueries by delegate {

    override suspend fun fillMissingRate(code: String, rate: BigDecimal): Int {
        val changed = delegate.fillMissingRate(code, rate)
        if (failAfterFill) throw InjectedFault()
        return changed
    }
}

/**
 * A runner with real all-or-nothing behaviour over the in-memory fakes: it remembers the entries (and
 * saved rates) before the block and puts them back when the block throws. [DirectTransactionRunner]
 * cannot prove atomicity because it never undoes anything.
 */
class RollbackOnFailureRunner(
    private val entries: FakeEntryRepository,
    private val rates: FakeRateRepository? = null,
    private val debts: FakeDebtRepository? = null,
    private val categories: FakeCategoryRepository? = null,
) : TransactionRunner {

    var runs = 0
        private set
    var isRunning = false
        private set

    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        runs++
        val previouslyRunning = isRunning
        isRunning = true
        val debtsBefore = debts?.stored.orEmpty()
        val categoriesBefore = categories?.stored.orEmpty()
        val rowsBefore = entries.rows.value
        val ratesBefore = rates?.observeAll()?.first().orEmpty()
        try {
            return block()
        } catch (t: Throwable) {
            val keptIds = rowsBefore.map { it.header.id }.toSet()
            entries.rows.value.map { it.header.id }.filterNot { it in keptIds }.forEach { entries.delete(it) }
            rowsBefore.forEach { entries.seedRaw(it) }
            rates?.restoreSnapshot(ratesBefore)
            debts?.restoreSnapshot(debtsBefore)
            categories?.restoreSnapshot(categoriesBefore)
            throw t
        } finally {
            isRunning = previouslyRunning
        }
    }
}

/** The fixed accounts, categories and currencies the entry write-path tests share. */
object EntryWorld {
    val usdAccount = account(1, "USD")
    val sypAccount = account(2, "SYP")
    val secondUsdAccount = account(3, "USD")
    val incomeCategory = Category(id = 5, name = "Salary", kind = CategoryKind.INCOME)
    val expenseCategory = Category(id = 6, name = "Food", kind = CategoryKind.EXPENSE)
    val currencies = listOf(Currency("USD", "$", 2), Currency("SYP", "£S", 2), Currency("EUR", "€", 2))
    /** A sub-category of [expenseCategory], and one that hangs under [incomeCategory]. */
    val foodSubCategory = Category(id = 7, name = "Lunch", kind = CategoryKind.EXPENSE, parentId = 6)
    val salarySubCategory = Category(id = 8, name = "Bonus", kind = CategoryKind.INCOME, parentId = 5)
    val day: LocalDate = LocalDate.of(2026, 10, 9)

    fun account(id: Long, code: String) =
        Account(id = id, name = "Account $id", typeKey = "cash", currencyCode = code, openingBalance = Money.zero(code))

    fun expense(
        id: Long = 0,
        accountId: Long = 1,
        amount: String = "10",
        currency: String = "USD",
        categoryId: Long = 6,
        rate: String? = null,
        note: String? = null,
        subCategoryId: Long? = null,
    ) = Transaction.Expense(
        id = id,
        accountId = accountId,
        amount = Money.of(amount, currency),
        categoryId = categoryId,
        subCategoryId = subCategoryId,
        date = day,
        note = note,
        rateToBase = rate?.let(::BigDecimal),
    )

    fun income(
        id: Long = 0,
        accountId: Long = 1,
        amount: String = "10",
        currency: String = "USD",
        categoryId: Long = 5,
        rate: String? = null,
        subCategoryId: Long? = null,
    ) = Transaction.Income(
        id = id,
        accountId = accountId,
        amount = Money.of(amount, currency),
        categoryId = categoryId,
        subCategoryId = subCategoryId,
        date = day,
        rateToBase = rate?.let(::BigDecimal),
    )

    fun transfer(
        id: Long = 0,
        sourceAccountId: Long = 1,
        destAccountId: Long = 3,
        amount: String = "10",
        from: String = "USD",
        destAmount: String = amount,
        to: String = from,
        rateUsed: String = "1",
    ) = Transaction.Transfer(
        id = id,
        sourceAccountId = sourceAccountId,
        destAccountId = destAccountId,
        sourceAmount = Money.of(amount, from),
        destAmount = Money.of(destAmount, to),
        rateUsed = BigDecimal(rateUsed),
        date = day,
    )
}

/**
 * Wires [SaveEntry], [DeleteEntry] and [RestoreEntries] over the in-memory fakes. [entries] is the
 * store tests assert on; [faultAfterWrite] makes the use cases see a repository that fails right
 * after a write, and [atomic] picks a runner that undoes a failed block.
 *
 * @param savedRates Saved rates to the base, e.g. `"SYP" to "0.0001"`.
 */
class SaveEntryRig(
    initialEntries: List<Transaction> = emptyList(),
    categories: List<Category> = listOf(EntryWorld.incomeCategory, EntryWorld.expenseCategory),
    accounts: List<Account> = listOf(EntryWorld.usdAccount, EntryWorld.sypAccount, EntryWorld.secondUsdAccount),
    savedRates: Map<String, String> = emptyMap(),
    base: String = "USD",
    saveRates: Boolean = true,
    faultAfterWrite: Boolean = false,
    faultAfterFill: Boolean = false,
    atomic: Boolean = false,
    initialDebts: List<Debt> = emptyList(),
    initialDeductionItems: List<org.aristonis.mywallet.domain.model.DeductionItem> = emptyList(),
    roleBindings: Map<CategoryRole, Long> = emptyMap(),
    private val currencies: List<Currency> = EntryWorld.currencies,
) {
    val deductions = FakeDeductionItemRepository(initialDeductionItems)
    val debts = FakeDebtRepository(initialDebts)
    val roles = FakeCategoryRoleRepository(roleBindings)
    val entries = FakeEntryRepository(initialEntries)
    val categoryRepository = FakeCategoryRepository(categories)
    private val initialRates = savedRates.map { (code, rate) -> ExchangeRate(code, BigDecimal(rate)) }
    val rates = FakeRateRepository(initialRates)
    val runner: TransactionRunner =
        if (atomic) RollbackOnFailureRunner(entries, rates, debts, categoryRepository) else DirectTransactionRunner()

    private val entryPort: EntryRepository = FaultyEntryRepository(entries, faultAfterWrite)
    val accountRepository = FakeAccountRepository(accounts)
    private val currencyRepository = FakeCurrencyRepository(currencies)
    private val settingsRepository = FakeSettingsRepository(Settings(baseCurrencyCode = base, saveTransactionRates = saveRates))

    /** The one ledger the save and the rate use case both write through, so a fault reaches both. */
    val ledger: LedgerQueries = FaultyLedgerQueries(
        FakeLedgerQueries(entries),
        faultAfterFill,
    )

    val saveEntry = SaveEntry(
        runner = runner,
        accounts = accountRepository,
        categories = categoryRepository,
        entries = entryPort,
        fx = fakeFx(currencies, initialRates, base),
        settings = settingsRepository,
        setExchangeRate = SetExchangeRate(currencyRepository, rates, ledger, runner, settingsRepository),
        debts = debts,
        ledger = ledger,
        roleCategories = RoleCategories(roles, categoryRepository),
        deductions = deductions,
    )

    val deleteEntry = DeleteEntry(runner = runner, entries = entryPort, debts = debts, ledger = ledger)

    val restoreEntries = RestoreEntries(
        runner = runner,
        entries = entryPort,
        categories = categoryRepository,
        accounts = accountRepository,
        debts = debts,
        ledger = ledger,
        deductions = deductions,
    )
}
