package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.aristonis.mywallet.domain.port.TransactionRepository
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.model.FxSnapshot
import kotlinx.coroutines.flow.combine

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
    override fun observeAll(): Flow<List<Category>> = items
    override suspend fun findById(id: Long): Category? = items.value.firstOrNull { it.id == id }

    // The transaction screens only read categories — managing them is a different screen. Failing
    // here rather than returning something plausible keeps a drifting test from passing quietly.
    override suspend fun upsert(category: Category): Long =
        error("the transaction screens do not write categories")

    override suspend fun deleteAndReassign(categoryId: Long, kind: CategoryKind, fallbackKey: String): Int =
        error("the transaction screens do not delete categories")
}

internal class FakeTransactionRepository(initial: List<Transaction> = emptyList()) : TransactionRepository {
    private val items = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1

    /** Everything currently persisted, for assertions. */
    val added: List<Transaction> get() = items.value

    override fun observeAll(): Flow<List<Transaction>> = items
    override suspend fun findById(id: Long): Transaction? = items.value.firstOrNull { it.id == id }
    override suspend fun add(transaction: Transaction): Long {
        items.value = items.value + transaction
        return nextId++
    }

    override suspend fun update(transaction: Transaction) {
        items.value = items.value.map { if (it.id == transaction.id) transaction else it }
    }

    override suspend fun delete(id: Long) {
        items.value = items.value.filterNot { it.id == id }
    }
}

internal class FakeCurrencyRepository(initial: List<Currency> = emptyList()) : CurrencyRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Currency>> = items
    override suspend fun findByCode(code: String): Currency? = items.value.firstOrNull { it.code == code }
}

internal class FakeRateRepository(initial: List<ExchangeRate> = emptyList()) : RateRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<ExchangeRate>> = items
    override suspend fun findByCode(code: String): ExchangeRate? = items.value.firstOrNull { it.currencyCode == code }
    override suspend fun upsert(rate: ExchangeRate) {
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
