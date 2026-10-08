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
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.port.TransactionRepository
import org.aristonis.mywallet.data.db.UPSERT_NO_ROW_ID
import org.aristonis.mywallet.domain.error.WalletException

/**
 * The Room-backed implementations of the domain ports. Each one is a thin adapter: call the DAO,
 * map entities ↔ domain. Business rules stay in the use-cases; these are pure CRUD.
 */

class RoomAccountRepository(private val dao: AccountDao) : AccountRepository {
    override fun observeAll(): Flow<List<Account>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }
    override suspend fun findById(id: Long): Account? = dao.findById(id)?.toDomain()
    override suspend fun upsert(account: Account): Long = dao.upsert(account.toEntity())
    override suspend fun delete(id: Long) = dao.deleteById(id)
}

class RoomTransactionRepository(private val dao: TransactionDao) : TransactionRepository {
    override fun observeAll(): Flow<List<Transaction>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }
    override fun observeBetween(range: DateRange): Flow<List<Transaction>> =
        dao.observeBetween(range.start, range.endInclusive).map { rows -> rows.map { it.toDomain() } }

    override suspend fun findById(id: Long): Transaction? = dao.findById(id)?.toDomain()
    override suspend fun add(transaction: Transaction): Long = dao.insert(transaction.toEntity())
    override suspend fun update(transaction: Transaction) = dao.update(transaction.toEntity())
    override suspend fun delete(id: Long) = dao.deleteById(id)
}

class RoomCategoryRepository(private val dao: CategoryDao) : CategoryRepository {
    override fun observeAll(): Flow<List<Category>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }
    override suspend fun findById(id: Long): Category? = dao.findById(id)?.toDomain()

    override suspend fun upsert(category: Category): Long {
        val rowId = dao.upsert(category.toEntity())
        if (rowId != UPSERT_NO_ROW_ID) return rowId
        // Room reports no row id when the upsert took its UPDATE branch. For a row that arrived with
        // an id that is simply its own id. For a new row it means the insert lost a uniqueness check
        // and the update then matched nothing, so nothing was written — reporting that back as a
        // successful save would hand the caller an id for a row that does not exist.
        if (category.id == 0L) throw WalletException.DuplicateCategoryName(category.name)
        return category.id
    }

    override suspend fun deleteAndReassign(categoryId: Long, kind: CategoryKind, fallbackKey: String): Int =
        dao.deleteAndReassign(
            categoryId = categoryId,
            kind = kind.name,
            fallbackKey = fallbackKey,
            // The key doubles as the bucket's stored name. Nothing displays it — the screen resolves
            // a label from the key — and it deliberately is not a word anyone would type, because the
            // name still shares a uniqueness namespace with user names. A friendlier placeholder
            // would collide with a user's own "Uncategorized" and dead-end every later delete of
            // that kind, since the bucket could then never be created.
            fallbackName = fallbackKey,
        )
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
