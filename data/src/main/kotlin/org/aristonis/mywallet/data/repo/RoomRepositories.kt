package org.aristonis.mywallet.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import org.aristonis.mywallet.data.db.AccountDao
import org.aristonis.mywallet.data.db.CategoryDao
import org.aristonis.mywallet.data.db.CurrencyDao
import org.aristonis.mywallet.data.db.RateDao
import org.aristonis.mywallet.data.db.SettingsDao
import org.aristonis.mywallet.data.db.TransactionDao
import org.aristonis.mywallet.data.db.toDomain
import org.aristonis.mywallet.data.db.toEntity
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
 * The Room-backed implementations of the domain ports. Each one is a thin adapter: call the DAO,
 * map entities ↔ domain. Business rules stay in the use-cases; these are pure CRUD.
 */

class RoomAccountRepository(private val dao: AccountDao) : AccountRepository {
    override fun observeAll(): Flow<List<Account>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }
    override suspend fun findById(id: Long): Account? = dao.findById(id)?.toDomain()
    override suspend fun upsert(account: Account): Long = dao.upsert(account.toEntity())
}

class RoomTransactionRepository(private val dao: TransactionDao) : TransactionRepository {
    override fun observeAll(): Flow<List<Transaction>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }
    override suspend fun add(transaction: Transaction): Long = dao.insert(transaction.toEntity())
}

class RoomCategoryRepository(private val dao: CategoryDao) : CategoryRepository {
    override fun observeAll(): Flow<List<Category>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }
    override suspend fun findById(id: Long): Category? = dao.findById(id)?.toDomain()
}

class RoomCurrencyRepository(private val dao: CurrencyDao) : CurrencyRepository {
    override fun observeAll(): Flow<List<Currency>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }
    override suspend fun findByCode(code: String): Currency? = dao.findByCode(code)?.toDomain()
}

class RoomRateRepository(private val dao: RateDao) : RateRepository {
    override fun observeAll(): Flow<List<ExchangeRate>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }
    override suspend fun findByCode(code: String): ExchangeRate? = dao.findByCode(code)?.toDomain()
    override suspend fun upsert(rate: ExchangeRate) = dao.upsert(rate.toEntity())
}

class RoomSettingsRepository(private val dao: SettingsDao) : SettingsRepository {
    override fun observe(): Flow<Settings> = dao.observe().filterNotNull().map { it.toDomain() }
    override fun observeOrNull(): Flow<Settings?> = dao.observe().map { it?.toDomain() }
    override suspend fun get(): Settings =
        dao.get()?.toDomain() ?: error("settings not initialized — onboarding must set the base currency first")
    override suspend fun save(settings: Settings) = dao.upsert(settings.toEntity())
}
