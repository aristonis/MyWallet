package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
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
}

internal class FakeTransactionRepository(initial: List<Transaction> = emptyList()) : TransactionRepository {
    private val items = MutableStateFlow(initial)
    private var nextId = initial.size.toLong() + 1

    /** Everything currently persisted, for assertions. */
    val added: List<Transaction> get() = items.value

    override fun observeAll(): Flow<List<Transaction>> = items
    override suspend fun add(transaction: Transaction): Long {
        items.value = items.value + transaction
        return nextId++
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
