package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.aristonis.mywallet.domain.model.DateRange
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.model.FxSnapshot
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.port.AccountLeg
import org.aristonis.mywallet.domain.port.CategoryLeg
import org.aristonis.mywallet.domain.port.EntryKey
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.LegRate
import org.aristonis.mywallet.domain.port.UnreadableRowRef
import org.aristonis.mywallet.domain.usecase.DeleteEntry
import org.aristonis.mywallet.domain.usecase.RestoreEntries
import org.aristonis.mywallet.domain.usecase.SaveEntry
import org.aristonis.mywallet.domain.usecase.SetExchangeRate
import java.math.BigDecimal

/**
 * Reactive in-memory ports shared by the two transaction view-model tests (both live in this package,
 * so the fakes are declared once here). Read sides are [MutableStateFlow]-backed so a write re-emits —
 * that is what lets a `GetAccountBalances` probe observe a recorded transaction move the balance.
 */

internal class FakeAccountRepository(initial: List<Account> = emptyList()) : AccountRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Account>> = items
    override suspend fun findById(id: Long): Account? = items.value.firstOrNull { it.id == id }
    override suspend fun upsert(account: Account): Long = account.id
    override suspend fun delete(id: Long) { items.value = items.value.filterNot { it.id == id } }
}

internal class FakeCategoryRepository(initial: List<Category> = emptyList()) : CategoryRepository {
    private val items = MutableStateFlow(initial)

    /** Every read of the categories fails, as one over a broken table would. */
    var failing = false

    override fun observeAll(): Flow<List<Category>> =
        flow { if (failing) throw IllegalStateException("categories could not be read") else emitAll(items) }
    override suspend fun findById(id: Long): Category? = items.value.firstOrNull { it.id == id }

    // The transaction screens only read categories — managing them is a different screen. Failing
    // here rather than returning something plausible keeps a drifting test from passing quietly.
    override suspend fun upsert(category: Category): Long =
        error("the transaction screens do not write categories")

    override suspend fun deleteAndReassign(categoryId: Long, kind: CategoryKind, fallbackKey: String): Int =
        error("the transaction screens do not delete categories")
}

internal class FakeCurrencyRepository(initial: List<Currency> = emptyList()) : CurrencyRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Currency>> = items
    override suspend fun findByCode(code: String): Currency? = items.value.firstOrNull { it.code == code }
}

internal class FakeRateRepository(initial: List<ExchangeRate> = emptyList()) : RateRepository {
    private val items = MutableStateFlow(initial)

    /** Every rate written through [upsert], in order; empty when nothing was saved. */
    val upserts = mutableListOf<ExchangeRate>()

    override fun observeAll(): Flow<List<ExchangeRate>> = items
    override suspend fun findByCode(code: String): ExchangeRate? = items.value.firstOrNull { it.currencyCode == code }
    override suspend fun upsert(rate: ExchangeRate) {
        upserts += rate
        items.value = items.value.filterNot { it.currencyCode == rate.currencyCode } + rate
    }
}

internal class FakeSettingsRepository(initial: Settings? = null) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override fun observe(): Flow<Settings> = state.filterNotNull()
    override fun observeOrNull(): Flow<Settings?> = state
    override suspend fun get(): Settings = state.value ?: error("settings not initialized")
    override suspend fun save(settings: Settings) { state.value = settings }
}

/**
 * Assembles the conversion context from the three fakes the screens already use, so a test that
 * upserts a rate still sees the flow re-emit. The real adapter does the same re-read inside one
 * transaction; that atomicity is what the instrumented tests prove, not this.
 */
internal class FakeFxRepository(
    private val currencies: CurrencyRepository,
    private val rates: RateRepository,
    private val settings: SettingsRepository,
) : FxRepository {
    override fun observeFx(): Flow<FxSnapshot> =
        combine(settings.observe(), rates.observeAll(), currencies.observeAll()) { s, r, c ->
            FxSnapshot(
                baseCurrencyCode = s.baseCurrencyCode,
                ratesToBase = r.associate { it.currencyCode to it.rateToBase },
                currencies = c,
            )
        }
}

/** Runs the block directly; the app tests check what gets written, not database transactions. */
internal class DirectTransactionRunner : org.aristonis.mywallet.domain.port.TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
}

/** Keeps a collector on the list's state for the rest of the test, as a visible screen would. */
internal fun TestScope.watch(viewModel: TransactionsListViewModel) {
    backgroundScope.launch { viewModel.state.collect {} }
}

/**
 * In-memory entries with the id rules the real repository has: id 0 gets a fresh id, an id above 0
 * is a plain insert that refuses a taken id (so an undo can never overwrite another entry).
 */
internal class FakeEntryRepository(initial: List<Transaction> = emptyList()) : EntryRepository {
    private val items = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1

    /** Everything currently stored, for assertions. */
    val stored: List<Transaction> get() = items.value

    /** When set, every write (insert, replace, delete) throws it, as a broken database would. */
    var failWith: Throwable? = null

    /** Inserts that reached the store, counting only those that did not fail. */
    var inserts = 0
        private set

    /** Replaces that reached the store, counting only those that did not fail. */
    var replaces = 0
        private set

    /** One paged read as the screen asked for it. */
    data class PageRead(val range: DateRange, val after: EntryKey?, val limit: Int)

    /** Paged reads that were started, in order; a read counts when someone begins collecting it. */
    val pageReads = mutableListOf<PageRead>()

    /** Reads of the whole range at once (observeAll, or observeBetween over all time) that were started. */
    var wholeHistoryReads = 0
        private set

    override fun observeAll(): Flow<List<Transaction>> = items.onStart { wholeHistoryReads++ }
    override fun observeBetween(range: DateRange): Flow<List<Transaction>> =
        items.map { all -> all.filter { it.date in range } }
            .onStart { if (range == DateRange.ALL_TIME) wholeHistoryReads++ }

    /** A paged read over exactly this range fails, as one over a row the app cannot decode would. */
    var unreadableRange: DateRange? = null

    /** The next this-many paged reads fail, then reads serve again (a read that fails once). */
    var failedReadsLeft = 0

    /** Once the failing reads are used up, paged reads never answer: the state a retry is in while it reads. */
    var hangsAfterFailures = false

    /** Paged reads someone is still collecting; zero once every screen has stopped watching. */
    var activeReads = 0
        private set

    /** Paged reads ever started; a restarted read counts again. */
    var readsOpened = 0
        private set

    /** Newest first, strictly after [after], like the real keyset read; re-emits on every write. */
    override fun observePage(range: DateRange, after: EntryKey?, limit: Int): Flow<List<Transaction>> =
        flow<List<Transaction>> {
            if (range == unreadableRange) throw IllegalStateException("a stored row could not be read")
            if (failedReadsLeft > 0) {
                failedReadsLeft--
                throw IllegalStateException("a stored row could not be read")
            }
            if (hangsAfterFailures) awaitCancellation()
            emitAll(items)
        }.map { all ->
            all.filter { it.date in range }
                .sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.id })
                .filter { after == null || it.date < after.date || (it.date == after.date && it.id < after.id) }
                .take(limit)
        }
            .onStart { pageReads += PageRead(range, after, limit); activeReads++; readsOpened++ }
            .onCompletion { activeReads-- }

    override suspend fun findById(id: Long): Transaction? = items.value.firstOrNull { it.id == id }

    override suspend fun insert(entry: Transaction): Long {
        failWith?.let { throw it }
        inserts++
        if (entry.id == 0L) {
            val id = nextId++
            items.value = items.value + entry.withId(id)
            return id
        }
        if (items.value.any { it.id == entry.id }) throw WalletException.EntryIdTaken(entry.id)
        items.value = items.value + entry
        nextId = maxOf(nextId, entry.id + 1)
        return entry.id
    }

    override suspend fun replace(entry: Transaction) {
        failWith?.let { throw it }
        replaces++
        items.value = items.value.map { if (it.id == entry.id) entry else it }
    }

    override suspend fun delete(id: Long) {
        failWith?.let { throw it }
        items.value = items.value.filterNot { it.id == id }
    }

    private fun Transaction.withId(id: Long): Transaction = when (this) {
        is Transaction.Income -> copy(id = id)
        is Transaction.Expense -> copy(id = id)
        is Transaction.Transfer -> copy(id = id)
    }
}

/**
 * Ledger projections the form tests never read; only the rate fill is recorded. The save keeps typed
 * rates through SetExchangeRate, which is wired to this same ledger, so its fills land here.
 */
internal class FakeLedgerQueries : LedgerQueries {
    /** Every (currency, rate) the save asked to fill into legs that had none. */
    val filledRates = mutableListOf<Pair<String, BigDecimal>>()

    override fun observeAccountLegs(): Flow<List<AccountLeg>> = flowOf(emptyList())
    override fun observeCategoryLegs(range: DateRange): Flow<List<CategoryLeg>> = flowOf(emptyList())
    override fun observeUnknownKind(known: Set<String>): Flow<UnreadableRowRef?> = flowOf(null)
    override suspend fun isAccountInUse(accountId: Long): Boolean = false
    override fun observeCategoryUsage(): Flow<Map<Long, Int>> = flowOf(emptyMap())
    override suspend fun fillMissingRate(code: String, rate: BigDecimal): Int {
        filledRates += code to rate
        return 0
    }
    override suspend fun distinctLegRates(): List<LegRate> = emptyList()
}

/**
 * The real entry use cases wired over the fakes above, so a form test sees exactly what SaveEntry,
 * DeleteEntry and RestoreEntries do: the checks they make, the rates they stamp, the date they store.
 */
internal class EntryUseCases(
    accounts: AccountRepository,
    categories: CategoryRepository,
    val entries: FakeEntryRepository,
    currencies: CurrencyRepository,
    rates: RateRepository,
    settings: SettingsRepository,
) {
    private val runner = DirectTransactionRunner()
    val ledger = FakeLedgerQueries()
    val saveEntry = SaveEntry(
        runner, accounts, categories, entries,
        FakeFxRepository(currencies, rates, settings), settings, SetExchangeRate(currencies, rates, ledger, runner, settings),
    )
    val deleteEntry = DeleteEntry(runner, entries)
    val restoreEntries = RestoreEntries(runner, entries, categories, accounts)
}
