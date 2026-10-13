package org.aristonis.mywallet.data.backup

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aristonis.mywallet.data.db.AccountDao
import org.aristonis.mywallet.data.db.CategoryDao
import org.aristonis.mywallet.data.db.CategoryRoleDao
import org.aristonis.mywallet.data.db.CurrencyDao
import org.aristonis.mywallet.data.db.DebtDao
import org.aristonis.mywallet.data.db.DeductionDao
import org.aristonis.mywallet.data.db.EntryDao
import org.aristonis.mywallet.data.db.LegacyRowDao
import org.aristonis.mywallet.data.db.RateDao
import org.aristonis.mywallet.data.db.SettingsDao
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.port.BackupRepository
import org.aristonis.mywallet.domain.port.PreparedBackup
import java.io.File
import java.io.InputStream

/**
 * Room-backed backup port. Export takes a one-shot snapshot of every table, checks it, and hands
 * back a writer for it. Restore decodes and validates first — so bad or newer-format input is
 * rejected before a single write — then wipes and re-inserts every table inside one Room
 * transaction: any failure rolls the whole thing back, so the database is never left
 * half-restored. Rows are inserted with their backed-up
 * ids so cross-row references (categories, accounts, debts, entries) stay intact.
 */
class RoomBackupRepository(
    private val database: WalletDatabase,
    private val accounts: AccountDao,
    private val entries: EntryDao,
    private val categories: CategoryDao,
    private val currencies: CurrencyDao,
    private val rates: RateDao,
    private val settings: SettingsDao,
    private val categoryRoles: CategoryRoleDao,
    private val legacyRows: LegacyRowDao,
    private val debts: DebtDao,
    private val deductions: DeductionDao,
    private val scratchDirectory: File,
) : BackupRepository {

    override suspend fun prepareBackup(): PreparedBackup {
        // One read transaction so the tables form a consistent snapshot: a write landing between two
        // reads can't yield a backup whose leg points at an entry the snapshot missed.
        val snapshot = database.withTransaction {
            TableSnapshot(
                settings = settings.get(),
                accounts = accounts.getAll(),
                categories = categories.getAll(),
                currencies = currencies.getAll(),
                rates = rates.getAll(),
                entries = entries.getAll(),
                legs = entries.getAllLegs(),
                debts = debts.getAll(),
                deductionItems = deductions.getAllItems(),
                deductionAutoCategories = deductions.getAllAutoCategories(),
                deductionLines = deductions.getAllLines(),
                categoryRoles = categoryRoles.getAll(),
                legacyRows = legacyRows.getAll(),
            )
        }
        // Building, checking and serializing the whole database are CPU-bound — keep them off the
        // caller's thread so a ViewModel-scope call doesn't run them on the UI thread. The check runs
        // here, before the caller opens its document, because opening for writing truncates it.
        val checked = withContext(Dispatchers.IO) { BackupCodec.validate(buildBackup(snapshot)) }
        return PreparedBackup { output -> withContext(Dispatchers.IO) { BackupCodec.encode(checked, output) } }
    }

    override suspend fun restoreBackup(input: InputStream) {
        // Decode + validate off the caller's thread and before the transaction opens, so bad or
        // newer-format input is rejected without a single write.
        val backup = withContext(Dispatchers.IO) { decodeValidated(input, scratchDirectory) }
        try {
            writeAll(backup)
        } catch (e: SQLiteConstraintException) {
            // The checks above cover every key and unique index the restore is known to hit; this
            // catches whatever they miss. The transaction has already rolled back, so the wallet is
            // as it was, and the owner hears that the file cannot be restored, not a database error.
            throw WalletException.BackupInvalid(e)
        }
    }

    private suspend fun writeAll(backup: WalletBackupV2) {
        database.withTransaction {
            clearAll()
            // Referenced rows go in before the rows that point at them. This order is required, not
            // decorative: legs, deduction lines and roles carry foreign keys and Room enables their
            // enforcement, so inserting a child first would abort the restore. Reordering these
            // lines breaks a restore.
            currencies.insertAll(backup.currencies.map { it.toEntity() })
            categories.insertAll(backup.categories.map { it.toEntity() })
            accounts.insertAll(backup.accounts.map { it.toEntity() })
            rates.insertAll(backup.rates.map { it.toEntity() })
            backup.settings?.let { settings.upsert(it.toEntity()) }
            debts.insertAll(backup.debts.map { it.toEntity() })
            deductions.insertItems(backup.deductionItems.map { it.toEntity() })
            deductions.insertAutoCategories(backup.deductionAutoCategories.map { it.toEntity() })
            entries.insertAll(backup.entries.map { it.toEntity() })
            deductions.insertLines(backup.deductionLines.map { it.toEntity() })
            entries.insertLegs(backup.legs.map { it.toEntity() })
            categoryRoles.insertAll(backup.categoryRoles.map { it.toEntity() })
            legacyRows.insertAll(backup.legacyRows.map { it.toEntity() })
            val highestEntryId = backup.entries.maxOfOrNull { it.id } ?: 0L
            val highestRawCopyId = backup.legacyRows.maxOfOrNull { it.id } ?: 0L
            continueEntryIdsAfter(maxOf(highestEntryId, highestRawCopyId))
        }
    }

    /**
     * Children before parents: deleting the entries takes their legs and deduction lines with them,
     * which a debt or a deduction item must be free of before it can go.
     */
    private suspend fun clearAll() {
        legacyRows.clear()
        categoryRoles.clear()
        entries.clear()
        deductions.clearItems()
        debts.clear()
        settings.clear()
        rates.clear()
        accounts.clear()
        categories.clear()
        currencies.clear()
    }

    /**
     * Sets where new entry ids continue from. Without it the sequence would keep whatever the wiped
     * wallet had reached, and a raw copy whose entry the owner deleted would leave its id free to be
     * taken by a new entry, so one id would name two different records. `sqlite_sequence` is
     * SQLite's own table, which no DAO can name, so it is written directly, on the connection that
     * holds the restore's transaction.
     */
    private fun continueEntryIdsAfter(highestEntryId: Long) {
        val db = database.openHelper.writableDatabase
        db.execSQL("DELETE FROM sqlite_sequence WHERE name = 'entries'")
        db.execSQL("INSERT INTO sqlite_sequence (name, seq) VALUES ('entries', ?)", arrayOf<Any?>(highestEntryId))
    }
}
