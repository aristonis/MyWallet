package org.aristonis.mywallet.ui.home

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.aristonis.mywallet.RecordingErrorReporter
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountLeg
import org.aristonis.mywallet.domain.port.DebtLeg
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.UnreadableRowRef
import org.aristonis.mywallet.domain.usecase.ComputeHomeSummary
import org.aristonis.mywallet.domain.usecase.GetAccountBalances
import org.aristonis.mywallet.domain.usecase.GetAccountBalancesInBase
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
import org.aristonis.mywallet.ui.transaction.EntryUseCases
import org.aristonis.mywallet.ui.transaction.FakeAccountRepository
import org.aristonis.mywallet.ui.transaction.FakeCategoryRepository
import org.aristonis.mywallet.ui.transaction.FakeCurrencyRepository
import org.aristonis.mywallet.ui.transaction.FakeEntryRepository
import org.aristonis.mywallet.ui.transaction.FakeLedgerQueries
import org.aristonis.mywallet.ui.transaction.FakeRateRepository
import org.aristonis.mywallet.ui.transaction.FakeSettingsRepository
import java.time.LocalDate
import java.util.Locale

internal fun summaryAccount(id: Long, opening: String, code: String = "USD", type: String = "cash",
    archived: Boolean = false, limit: String? = null) = Account(id, "Account $id", type, code,
    Money.of(opening, code), archived = archived, creditLimit = limit?.let { Money.of(it, code) })

internal class HomeSummaryRig(accounts: List<Account>, private val work: CoroutineDispatcher) {
    val accountRepository = FakeAccountRepository(accounts)
    val entries = FakeEntryRepository()
    val debtLegs = MutableStateFlow<List<DebtLeg>>(emptyList())
    val unknown = MutableStateFlow<UnreadableRowRef?>(null)
    val currencies = FakeCurrencyRepository(listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)))
    val fxState = MutableStateFlow(FxSnapshot("USD", emptyMap(), listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2))))
    private val fx = object : FxRepository { override fun observeFx(): Flow<FxSnapshot> = fxState }
    val settings = FakeSettingsRepository(Settings(baseCurrencyCode = "USD"))
    val errors = RecordingErrorReporter()
    var accountProjectionReads = 0
    val ledger = object : LedgerQueries by FakeLedgerQueries(entries) {
        override fun observeAccountLegs(): Flow<List<AccountLeg>> = entries.observeAll().map { all ->
            accountProjectionReads++
            all.flatMap { entry -> entry.toParts().legs.mapNotNull { leg ->
                (leg.target as? LegTarget.Account)?.let { AccountLeg(it.id, leg.amount) }
            } }
        }
        override fun observeDebtLegs(): Flow<List<DebtLeg>> = debtLegs
        override fun observeUnknownKind(known: Set<String>): Flow<UnreadableRowRef?> = unknown
    }
    private val writers = EntryUseCases(accountRepository,
        FakeCategoryRepository(listOf(Category(6, "Food", CategoryKind.EXPENSE))), entries, currencies,
        FakeRateRepository(), settings)
    suspend fun spend(amount: String) = writers.saveEntry(Transaction.Expense(accountId = 1,
        amount = Money.of(amount, "USD"), categoryId = 6, date = LocalDate.of(2026, 10, 12)), emptyMap())
    fun vm() = HomeViewModel(GetAccountBalances(accountRepository, ledger), GetAccountBalancesInBase(fx),
        ComputeHomeSummary(ledger, fx), ObserveLedgerHealth(ledger), currencies, settings,
        MoneyFormatter(Locale.US), work, errors)
}
