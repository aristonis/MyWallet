package org.aristonis.mywallet.ui.debt

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.aristonis.mywallet.RecordingErrorReporter
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryRole
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.NameKey
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.DebtLeg
import org.aristonis.mywallet.domain.port.CategoryRoleRepository
import org.aristonis.mywallet.domain.port.DebtRepository
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.TransactionRunner
import org.aristonis.mywallet.domain.port.UnreadableRowRef
import org.aristonis.mywallet.domain.service.RoleCategories
import org.aristonis.mywallet.domain.usecase.DeleteDebt
import org.aristonis.mywallet.domain.usecase.PreviewDebtDeletion
import org.aristonis.mywallet.domain.usecase.RestoreEntries
import org.aristonis.mywallet.domain.usecase.SaveEntry
import org.aristonis.mywallet.domain.usecase.SetExchangeRate
import org.aristonis.mywallet.domain.usecase.WriteOffDebt
import org.aristonis.mywallet.domain.usecase.ObserveDebt
import org.aristonis.mywallet.domain.usecase.ObserveDebts
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
import org.aristonis.mywallet.domain.usecase.RecordRepayment
import org.aristonis.mywallet.domain.usecase.RenameDebt
import org.aristonis.mywallet.domain.usecase.UpdateDebtDetails
import org.aristonis.mywallet.ui.transaction.FakeAccountRepository
import org.aristonis.mywallet.ui.transaction.FakeCategoryRepository
import org.aristonis.mywallet.ui.transaction.FakeRateRepository
import org.aristonis.mywallet.ui.transaction.FakeSettingsRepository
import org.aristonis.mywallet.ui.transaction.FakeCurrencyRepository
import org.aristonis.mywallet.ui.transaction.FakeDebtRepository
import org.aristonis.mywallet.ui.transaction.FakeEntryRepository
import org.aristonis.mywallet.ui.transaction.FakeLedgerQueries
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

internal val screenDay: LocalDate = LocalDate.of(2026, 10, 12)
internal fun screenDebt(id: Long, name: String, side: DebtSide = DebtSide.I_OWE, code: String = "USD") =
    Debt(id, name, side, code)
internal fun screenOpening(debt: Debt, amount: String = "10", id: Long = debt.id) =
    Transaction.OwedBefore(id, debt.id, debt.side, Money.of(amount, debt.currencyCode), screenDay)

internal class DebtScreenRig(
    initialDebts: List<Debt>,
    initialEntries: List<Transaction>,
    private val work: CoroutineDispatcher,
    initialAccounts: List<Account> = emptyList(),
    initialCategories: List<Category> = emptyList(),
    private val roleBindings: Map<CategoryRole, Long> = emptyMap(),
) {
    val debtStore = FakeDebtRepository(initialDebts)
    val entries = FakeEntryRepository(initialEntries)
    val unknown = MutableStateFlow<UnreadableRowRef?>(null)
    val currencies = FakeCurrencyRepository(listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)))
    val fxState = MutableStateFlow(FxSnapshot("USD", emptyMap(), listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2))))
    val errors = RecordingErrorReporter()
    var readFailure: Throwable? = null
    var renameFailure: Throwable? = null
    var detailsFailure: Throwable? = null
    var beforeRename: suspend () -> Unit = {}
    var beforeDetails: suspend () -> Unit = {}
    var renameCalls = 0
    var detailsCalls = 0
    var clock: Clock = Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC)
    val today = TodaySource { clock }
    private val debtPort = object : DebtRepository by debtStore {
        override fun observeAll(): Flow<List<Debt>> = flow {
            readFailure?.let { throw it }
            emitAll(debtStore.observeAll())
        }
        override suspend fun rename(id: Long, counterparty: String) {
            renameCalls++
            beforeRename()
            renameFailure?.let { throw it }
            val original = debtStore.findById(id)!!
            val key = NameKey.of(counterparty)
            if (initialDebts.any { it.id != id && it.side == original.side && it.currencyCode == original.currencyCode && it.key == key }) {
                throw WalletException.DebtNameTaken(counterparty.trim())
            }
            debtStore.rename(id, counterparty)
        }
        override suspend fun updateDetails(id: Long, dueDate: LocalDate?, note: String?) {
            detailsCalls++
            beforeDetails()
            detailsFailure?.let { throw it }
            debtStore.updateDetails(id, dueDate, note)
        }
    }
    private val entryPort = object : EntryRepository by entries {
        override fun observeOnDebt(debtId: Long): Flow<List<Transaction>> = entries.observeOnDebt(debtId)
            .map { it.sortedWith(compareByDescending<Transaction> { entry -> entry.date }.thenByDescending { entry -> entry.id }) }
    }
    private val ledger = object : LedgerQueries by FakeLedgerQueries(entries) {
        override fun observeUnknownKind(known: Set<String>): Flow<UnreadableRowRef?> = unknown
        override fun observeDebtLegs(): Flow<List<DebtLeg>> = entries.observeAll().map { all ->
            all.flatMap { entry -> entry.toParts().legs.mapNotNull { leg ->
                (leg.target as? LegTarget.Debt)?.let { DebtLeg(it.id, it.side, leg.amount) }
            } }
        }
    }
    private val fx = object : FxRepository { override fun observeFx(): Flow<FxSnapshot> = fxState }
    private val runner = object : TransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }
    fun list() = DebtsViewModel(ObserveDebts(debtPort, ledger, fx, today.dates), ObserveLedgerHealth(ledger),
        currencies, MoneyFormatter(Locale.US), today, work, errors)
    private val categories = FakeCategoryRepository(initialCategories)
    private val accounts = FakeAccountRepository(initialAccounts)
    private val rates = FakeRateRepository()
    private val settings = FakeSettingsRepository(Settings(baseCurrencyCode = "USD"))
    private val roles = object : CategoryRoleRepository {
        override suspend fun categoryFor(role: CategoryRole): Long? = roleBindings[role]
        override suspend fun bindIfMissing(role: CategoryRole, categoryId: Long): Boolean =
            error("role binding is not exercised by this fixture")
    }
    private val save = SaveEntry(runner, accounts, categories, entryPort, fx, settings,
        SetExchangeRate(currencies, rates, ledger, runner, settings), debtPort, ledger, RoleCategories(roles, categories))
    fun detail() = DebtDetailViewModel(ObserveDebt(debtPort, ledger, entryPort, today.dates),
        RenameDebt(runner, debtPort), UpdateDebtDetails(runner, debtPort), currencies, MoneyFormatter(Locale.US),
        today, work, errors, ObserveLedgerHealth(ledger), categories, roles,
        WriteOffDebt(runner, debtPort, ledger, save), PreviewDebtDeletion(runner, debtPort, entryPort, ledger),
        DeleteDebt(runner, debtPort, entryPort, ledger),
        RestoreEntries(runner, entryPort, categories, accounts, debtPort, ledger))

    fun repayment() = RecordRepaymentViewModel(
        observeDebt = ObserveDebt(debtPort, ledger, entryPort, today.dates),
        accounts = accounts,
        categories = categories,
        fx = fx,
        recordRepayment = RecordRepayment(runner, debtPort, ledger, entryPort, accounts, fx, save),
        parser = MoneyParser(Locale.US),
        today = today,
        formatter = MoneyFormatter(Locale.US),
        errors = errors,
        roles = roles,
    )
}
