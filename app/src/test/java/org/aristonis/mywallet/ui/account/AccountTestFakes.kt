package org.aristonis.mywallet.ui.account

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.TransactionRepository

/**
 * Reactive in-memory ports shared by the account view-model tests (both live in this package, so the
 * fakes are declared once here — top-level `private` copies in two files would collide). Read sides
 * are [MutableStateFlow]-backed so a write re-emits, like the real Room streams.
 */

internal class FakeAccountRepository(initial: List<Account> = emptyList()) : AccountRepository {
    private val items = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1

    /** Everything currently persisted, for assertions. */
    val upserted: List<Account> get() = items.value

    override fun observeAll(): Flow<List<Account>> = items
    override suspend fun findById(id: Long): Account? = items.value.firstOrNull { it.id == id }
    override suspend fun upsert(account: Account): Long {
        val id = if (account.id == 0L) nextId++ else account.id
        items.value = items.value.filterNot { it.id == id } + account.copy(id = id)
        return id
    }
    override suspend fun delete(id: Long) { items.value = items.value.filterNot { it.id == id } }
}

internal class FakeCurrencyRepository(initial: List<Currency>) : CurrencyRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Currency>> = items
    override suspend fun findByCode(code: String): Currency? = items.value.firstOrNull { it.code == code }
}

internal class FakeTransactionRepository(initial: List<Transaction> = emptyList()) : TransactionRepository {
    private val items = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Transaction>> = items
    override suspend fun add(transaction: Transaction): Long = 1
    override suspend fun update(transaction: Transaction) { items.value = items.value.map { if (it.id == transaction.id) transaction else it } }
    override suspend fun delete(id: Long) { items.value = items.value.filterNot { it.id == id } }
}
