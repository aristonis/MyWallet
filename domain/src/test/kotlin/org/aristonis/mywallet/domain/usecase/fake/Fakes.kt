package org.aristonis.mywallet.domain.usecase.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Tiny in-memory implementations of the ports for use-case tests. Because the ports are interfaces,
 * no mocking framework is needed — these are just classes. Read sides are backed by a
 * [MutableStateFlow] so `observeAll()` behaves like the real Room streams (always a current value).
 */

class FakeAccountRepository(initial: List<Account> = emptyList()) : AccountRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Account>> = items
    override suspend fun findById(id: Long): Account? = items.value.firstOrNull { it.id == id }
}

class FakeCategoryRepository(initial: List<Category> = emptyList()) : CategoryRepository {
    private val items = MutableStateFlow(initial)
    override suspend fun findById(id: Long): Category? = items.value.firstOrNull { it.id == id }
}

class FakeTransactionRepository(initial: List<Transaction> = emptyList()) : TransactionRepository {
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

class FakeCurrencyRepository(initial: List<Currency> = emptyList()) : CurrencyRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Currency>> = items
    override suspend fun findByCode(code: String): Currency? = items.value.firstOrNull { it.code == code }
}

class FakeRateRepository(initial: List<ExchangeRate> = emptyList()) : RateRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<ExchangeRate>> = items
    override suspend fun findByCode(code: String): ExchangeRate? =
        items.value.firstOrNull { it.currencyCode == code }
}

class FakeSettingsRepository(settings: Settings) : SettingsRepository {
    private val state = MutableStateFlow(settings)
    override fun observe(): Flow<Settings> = state
    override suspend fun get(): Settings = state.value
}
