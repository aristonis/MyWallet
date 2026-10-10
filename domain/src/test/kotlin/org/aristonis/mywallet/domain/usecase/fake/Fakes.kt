package org.aristonis.mywallet.domain.usecase.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.BaseCurrencyRepository
import org.aristonis.mywallet.domain.port.BackupRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.port.LegRateChange
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository

/**
 * Tiny in-memory implementations of the ports for use-case tests. Because the ports are interfaces,
 * no mocking framework is needed — these are just classes. Read sides are backed by a
 * [MutableStateFlow] so `observeAll()` behaves like the real Room streams (always a current value).
 */

class FakeAccountRepository(initial: List<Account> = emptyList()) : AccountRepository {
    private val items = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1
    override fun observeAll(): Flow<List<Account>> = items
    override suspend fun findById(id: Long): Account? = items.value.firstOrNull { it.id == id }
    override suspend fun upsert(account: Account): Long {
        val id = if (account.id == 0L) nextId++ else account.id
        items.value = items.value.filterNot { it.id == id } + account.copy(id = id)
        return id
    }

    override suspend fun delete(id: Long) {
        items.value = items.value.filterNot { it.id == id }
    }
}

class FakeCategoryRepository(initial: List<Category> = emptyList()) : CategoryRepository {
    private val items = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1

    /** Everything currently stored, for assertions. */
    val stored: List<Category> get() = items.value

    fun restoreSnapshot(snapshot: List<Category>) { items.value = snapshot }

    /**
     * Records what the use-case asked for. The real reassignment is a multi-table statement that
     * only Room can run atomically, so the domain tests assert the CONTRACT (which category, which
     * fallback) and the instrumented data tests prove the column-level effect.
     */
    var lastDeleteCall: DeleteCall? = null
        private set

    data class DeleteCall(val categoryId: Long, val kind: CategoryKind, val fallbackKey: String)

    /** How many transactions the next [deleteAndReassign] should claim to have touched. */
    var affectedRows: Int = 0

    override fun observeAll(): Flow<List<Category>> = items
    override suspend fun findById(id: Long): Category? = items.value.firstOrNull { it.id == id }

    override suspend fun upsert(category: Category): Long {
        val id = if (category.id == 0L) nextId++ else category.id
        items.value = items.value.filterNot { it.id == id } + category.copy(id = id)
        return id
    }

    override suspend fun deleteAndReassign(categoryId: Long, kind: CategoryKind, fallbackKey: String): Int {
        lastDeleteCall = DeleteCall(categoryId, kind, fallbackKey)
        val doomed = items.value.filter { it.id == categoryId || it.parentId == categoryId }.map { it.id }.toSet()
        items.value = items.value.filterNot { it.id in doomed }
        return affectedRows
    }

    override suspend fun bucketFor(kind: CategoryKind): Long {
        val key = Category.uncategorizedKeyFor(kind)
        items.value.firstOrNull { it.systemKey == key }?.let { return it.id }
        return upsert(Category(name = key, kind = kind, systemKey = key))
    }
}

class FakeCurrencyRepository(initial: List<Currency> = emptyList()) : CurrencyRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Currency>> = items
    override suspend fun findByCode(code: String): Currency? = items.value.firstOrNull { it.code == code }
}

class FakeRateRepository(initial: List<ExchangeRate> = emptyList()) : RateRepository {
    private val items = MutableStateFlow(initial)
    fun restoreSnapshot(snapshot: List<ExchangeRate>) { items.value = snapshot }
    override fun observeAll(): Flow<List<ExchangeRate>> = items
    override suspend fun findByCode(code: String): ExchangeRate? =
        items.value.firstOrNull { it.currencyCode == code }
    override suspend fun upsert(rate: ExchangeRate) {
        items.value = items.value.filterNot { it.currencyCode == rate.currencyCode } + rate
    }
}

/**
 * Serves one snapshot at a time, so a test can prove that a consumer reads the base currency and the
 * rates as a single unit — swapping both together is exactly what the real transactional read gives.
 */
class FakeFxRepository(initial: FxSnapshot) : FxRepository {
    private val state = MutableStateFlow(initial)
    override fun observeFx(): Flow<FxSnapshot> = state

    /** Replaces the whole context at once, the way a re-based read arrives. */
    fun emit(snapshot: FxSnapshot) { state.value = snapshot }
}

/** Records the one atomic hand-over, so a test can assert what the adapter was actually given. */
class FakeBaseCurrencyRepository : BaseCurrencyRepository {
    data class Rebase(
        val newBaseCurrencyCode: String,
        val rates: List<ExchangeRate>,
        val legRates: List<LegRateChange> = emptyList(),
    )

    var lastRebase: Rebase? = null
        private set
    var rebaseCount: Int = 0
        private set

    override suspend fun rebase(
        newBaseCurrencyCode: String,
        rates: List<ExchangeRate>,
        legRates: List<LegRateChange>,
    ) {
        lastRebase = Rebase(newBaseCurrencyCode, rates, legRates)
        rebaseCount++
    }
}

/** Builds the fake conversion context the way the real transactional read assembles it. */
fun fakeFx(
    currencies: List<Currency> = emptyList(),
    rates: List<ExchangeRate> = emptyList(),
    base: String = "USD",
) = FakeFxRepository(
    FxSnapshot(
        baseCurrencyCode = base,
        ratesToBase = rates.associate { it.currencyCode to it.rateToBase },
        currencies = currencies,
    ),
)

class FakeSettingsRepository(initial: Settings? = null) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override fun observe(): Flow<Settings> = state.filterNotNull()
    override fun observeOrNull(): Flow<Settings?> = state
    override suspend fun get(): Settings = state.value ?: error("settings not initialized")
    override suspend fun save(settings: Settings) {
        state.value = settings
    }
}

/**
 * Export hands back a canned string; restore records the string it was given (or throws a preset
 * failure) so a use-case test can prove forwarding happens and that errors are not swallowed.
 */
class FakeBackupRepository(
    private val exported: String = "",
    private val restoreFailure: Throwable? = null,
) : BackupRepository {
    /** The string passed to the last [restoreBackup] call, for assertions. */
    var restoredWith: String? = null
        private set

    override suspend fun exportBackup(): String = exported

    override suspend fun restoreBackup(serialized: String) {
        restoreFailure?.let { throw it }
        restoredWith = serialized
    }
}

/** Runs the block directly: the fakes have no transaction to open, and ordering is what tests check. */
class DirectTransactionRunner : org.aristonis.mywallet.domain.port.TransactionRunner {
    var runs = 0
        private set

    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        runs++
        return block()
    }
}
