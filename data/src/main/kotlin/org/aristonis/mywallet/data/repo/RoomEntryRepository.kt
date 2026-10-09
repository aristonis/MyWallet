package org.aristonis.mywallet.data.repo

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.aristonis.mywallet.data.db.EntryDao
import org.aristonis.mywallet.data.db.EntryEntity
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.data.db.toRows
import org.aristonis.mywallet.data.db.toTransactions
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.TransactionRepository

/**
 * Stores each entry as a header plus legs, and reads them back through the kind's recipe, so a row
 * no recipe can read surfaces as [WalletException.UnreadableRow] naming it.
 *
 * Every write touches two tables, so each runs in its own Room transaction (joining the caller's
 * when there is one): a header never lands without its legs.
 *
 * It also serves the old [TransactionRepository] port, so every caller written against it keeps
 * working on the new tables until it moves: `add` is [insert] and `update` is [replace].
 */
class RoomEntryRepository(
    private val database: WalletDatabase,
    private val dao: EntryDao,
) : EntryRepository, TransactionRepository {

    override fun observeAll(): Flow<List<Transaction>> = observeBetween(DateRange.ALL_TIME)

    override fun observeBetween(range: DateRange): Flow<List<Transaction>> =
        dao.observeBetween(range.start, range.endInclusive).map { rows -> rows.toTransactions() }

    override suspend fun findById(id: Long): Transaction? = dao.findById(id).toTransactions().singleOrNull()

    /**
     * @throws WalletException.EntryIdTaken when [entry] asks for an id another entry already holds.
     *   The plain insert aborts on the primary key, so nothing is written and nothing overwritten.
     */
    override suspend fun insert(entry: Transaction): Long {
        // Built before the transaction opens: an entry that cannot be turned into legs fails
        // without having touched the database.
        val stored = entry.toParts().toRows()
        return database.withTransaction {
            val id = insertHeader(stored.entry)
            dao.insertLegs(stored.legs.map { it.copy(entryId = id) })
            id
        }
    }

    /**
     * Only a header with an id of its own can clash with another. A constraint failure on a fresh
     * header is something else and goes up unchanged; so does one on a header whose id turns out
     * to be free, which is checked inside the same transaction rather than assumed.
     */
    private suspend fun insertHeader(header: EntryEntity): Long = try {
        dao.insertEntry(header)
    } catch (e: SQLiteConstraintException) {
        if (header.id > 0 && dao.exists(header.id)) throw WalletException.EntryIdTaken(header.id)
        throw e
    }

    /**
     * Rewrites the header and legs of an existing entry.
     *
     * The entry's deduction lines are deleted here and not written back: they are not part of a
     * [Transaction], so the use case that owns deductions writes them again after this call, in the
     * same transaction.
     *
     * @throws WalletException.TransactionNotFound when no entry has that id.
     */
    override suspend fun replace(entry: Transaction) {
        val stored = entry.toParts().toRows()
        database.withTransaction {
            // The header goes first: when it matches nothing the entry is gone, and failing here
            // leaves no orphaned legs behind.
            if (dao.updateEntry(stored.entry) == 0) throw WalletException.TransactionNotFound(entry.id)
            dao.deleteDeductionLinesOf(entry.id)
            dao.deleteLegsOf(entry.id)
            dao.insertLegs(stored.legs)
        }
    }

    override suspend fun delete(id: Long) = dao.deleteById(id)

    override suspend fun add(transaction: Transaction): Long = insert(transaction)

    override suspend fun update(transaction: Transaction) = replace(transaction)
}
