package org.aristonis.mywallet.ui.debt

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.port.DebtLeg
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.RecordingErrorReporter
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.CategoryRole
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CategoryRoleRepository
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.port.TransactionRunner
import org.aristonis.mywallet.domain.service.RoleCategories
import org.aristonis.mywallet.domain.usecase.DeleteDebt
import org.aristonis.mywallet.domain.usecase.ObserveDebt
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
import org.aristonis.mywallet.domain.usecase.PreviewDebtDeletion
import org.aristonis.mywallet.domain.usecase.RenameDebt
import org.aristonis.mywallet.domain.usecase.RestoreEntries
import org.aristonis.mywallet.domain.usecase.SaveEntry
import org.aristonis.mywallet.domain.usecase.SetExchangeRate
import org.aristonis.mywallet.domain.usecase.UpdateDebtDetails
import org.aristonis.mywallet.domain.usecase.WriteOffDebt
import org.aristonis.mywallet.ui.transaction.FakeAccountRepository
import org.aristonis.mywallet.ui.transaction.FakeCategoryRepository
import org.aristonis.mywallet.ui.transaction.FakeCurrencyRepository
import org.aristonis.mywallet.ui.transaction.FakeDebtRepository
import org.aristonis.mywallet.ui.transaction.FakeEntryRepository
import org.aristonis.mywallet.ui.transaction.FakeFxRepository
import org.aristonis.mywallet.ui.transaction.FakeLedgerQueries
import org.aristonis.mywallet.ui.transaction.FakeRateRepository
import org.aristonis.mywallet.ui.transaction.FakeSettingsRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

internal class DebtActionFixture(work: CoroutineDispatcher, val debt: Debt = screenDebt(1, "Ali"),
    initial: List<Transaction> = listOf(screenOpening(debt, "100"))) {
    val debtStore = FakeDebtRepository(listOf(debt, screenDebt(2, "Sara", DebtSide.OWED_TO_ME)))
    val entries = FakeEntryRepository(initial)
    private val categoryStore = FakeCategoryRepository(listOf(Category(5, "Debt forgiven", CategoryKind.INCOME),
        Category(6, "Lost debt", CategoryKind.EXPENSE), Category(7, "Other loss", CategoryKind.EXPENSE),
        Category(8, "Loss child", CategoryKind.EXPENSE, parentId = 6)))
    private val removedCategoryIds = MutableStateFlow<Set<Long>>(emptySet())
    val categories: CategoryRepository = object : CategoryRepository by categoryStore {
        override fun observeAll(): Flow<List<Category>> = combine(categoryStore.observeAll(), removedCategoryIds) {
            rows, removed -> rows.filterNot { it.id in removed }
        }
        override suspend fun findById(id: Long): Category? =
            if (id in removedCategoryIds.value) null else categoryStore.findById(id)
    }
    fun removeCategory(id: Long) { removedCategoryIds.value = removedCategoryIds.value + id }
    val errors = RecordingErrorReporter()
    var roleLookup: suspend (CategoryRole) -> Long? = { if (it == CategoryRole.DEBT_FORGIVEN) 5 else 6 }
    var beforeDelete: suspend () -> Unit = {}
    var beforeTransaction: suspend () -> Unit = {}
    var deleteCalls = 0
    var renameCalls = 0
    var detailsCalls = 0
    private val roles = object : CategoryRoleRepository {
        override suspend fun categoryFor(role: CategoryRole): Long? = roleLookup(role)
        override suspend fun bindIfMissing(role: CategoryRole, categoryId: Long): Boolean =
            error("role binding is not exercised by this fixture")
    }
    private val runner = object : TransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            beforeTransaction()
            val before = entries.stored
            try { return block() } catch (failure: Throwable) {
                entries.restoreSnapshot(before)
                throw failure
            }
        }
    }
    private val entryPort = object : EntryRepository by entries {
        override suspend fun delete(id: Long) {
            deleteCalls++
            beforeDelete()
            entries.delete(id)
        }
    }
    private val debtPort = object : org.aristonis.mywallet.domain.port.DebtRepository by debtStore {
        override suspend fun rename(id: Long, counterparty: String) { renameCalls++; debtStore.rename(id, counterparty) }
        override suspend fun updateDetails(id: Long, dueDate: java.time.LocalDate?, note: String?) {
            detailsCalls++
            debtStore.updateDetails(id, dueDate, note)
        }
    }
    private val ledger = object : LedgerQueries by FakeLedgerQueries(entries) {
        override fun observeDebtLegs(): Flow<List<DebtLeg>> = entries.observeAll().map { all ->
            all.flatMap { entry -> entry.toParts().legs.mapNotNull { leg ->
                (leg.target as? LegTarget.Debt)?.let { DebtLeg(it.id, it.side, leg.amount) }
            } }
        }
    }
    private val codes = FakeCurrencyRepository(listOf(Currency("USD", "$", 2)))
    private val accounts = FakeAccountRepository(listOf(Account(1, "Cash", "cash", "USD", Money.zero("USD"))))
    private val rates = FakeRateRepository()
    private val settings = FakeSettingsRepository(Settings(baseCurrencyCode = "USD"))
    private val fx: FxRepository = FakeFxRepository(codes, rates, settings)
    private val save = SaveEntry(runner, accounts, categories, entryPort, fx, settings,
        SetExchangeRate(codes, rates, ledger, runner, settings), debtPort, ledger, RoleCategories(roles, categories))
    private val today = TodaySource { Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC) }
    val vm = DebtDetailViewModel(ObserveDebt(debtPort, ledger, entryPort, today.dates),
        RenameDebt(runner, debtPort), UpdateDebtDetails(runner, debtPort), codes, MoneyFormatter(Locale.US),
        today, work, errors, ObserveLedgerHealth(ledger), categories = categories,
        roles = roles, writeOffDebt = WriteOffDebt(runner, debtPort, ledger, save),
        previewDebtDeletion = PreviewDebtDeletion(runner, debtPort, entryPort, ledger),
        deleteDebt = DeleteDebt(runner, debtPort, entryPort, ledger),
        restoreEntries = RestoreEntries(runner, entryPort, categories, accounts, debtPort, ledger))

}
