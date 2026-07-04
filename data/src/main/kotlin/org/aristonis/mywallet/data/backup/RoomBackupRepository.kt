package org.aristonis.mywallet.data.backup

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aristonis.mywallet.data.db.AccountDao
import org.aristonis.mywallet.data.db.CategoryDao
import org.aristonis.mywallet.data.db.CurrencyDao
import org.aristonis.mywallet.data.db.RateDao
import org.aristonis.mywallet.data.db.SettingsDao
import org.aristonis.mywallet.data.db.TransactionDao
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.domain.port.BackupRepository

/**
 * Room-backed backup port. Export takes a one-shot snapshot of every table and encodes it. Restore
 * decodes and validates first — so bad or newer-format input is rejected before a single write —
 * then wipes and re-inserts all six tables inside one Room transaction: any failure rolls the whole
 * thing back, so the database is never left half-restored. Rows are inserted with their backed-up
 * ids so cross-row references (categories, accounts, transfers) stay intact.
 */
class RoomBackupRepository(
    private val database: WalletDatabase,
    private val accounts: AccountDao,
    private val transactions: TransactionDao,
    private val categories: CategoryDao,
    private val currencies: CurrencyDao,
    private val rates: RateDao,
    private val settings: SettingsDao,
) : BackupRepository {

    override suspend fun exportBackup(): String {
        // One read transaction so the six tables form a consistent snapshot: a write landing between
        // two reads can't yield a backup whose transaction points at an account the snapshot missed.
        val backup = database.withTransaction {
            buildBackup(
                accounts = accounts.getAll(),
                transactions = transactions.getAll(),
                categories = categories.getAll(),
                currencies = currencies.getAll(),
                rates = rates.getAll(),
                settings = settings.get(),
            )
        }
        // Serializing the whole database is CPU-bound — keep it off the caller's thread so a
        // ViewModel-scope call doesn't encode on the UI thread.
        return withContext(Dispatchers.Default) { BackupCodec.encode(backup) }
    }

    override suspend fun restoreBackup(serialized: String) {
        // Decode + validate off the caller's thread and before the transaction opens, so bad or
        // newer-format input is rejected without a single write.
        val backup = withContext(Dispatchers.Default) { decodeValidated(serialized) }
        database.withTransaction {
            clearAll()
            // Referenced rows (currencies, categories, accounts) go in before the rows that point at
            // them; there are no FK constraints, but keeping this order makes the intent explicit.
            currencies.insertAll(backup.currencies.map { it.toEntity() })
            categories.insertAll(backup.categories.map { it.toEntity() })
            accounts.insertAll(backup.accounts.map { it.toEntity() })
            rates.insertAll(backup.rates.map { it.toEntity() })
            backup.settings?.let { settings.upsert(it.toEntity()) }
            transactions.insertAll(backup.transactions.map { it.toEntity() })
        }
    }

    private suspend fun clearAll() {
        transactions.clear()
        settings.clear()
        rates.clear()
        accounts.clear()
        categories.clear()
        currencies.clear()
    }
}
