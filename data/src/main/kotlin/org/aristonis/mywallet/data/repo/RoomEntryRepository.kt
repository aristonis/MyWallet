package org.aristonis.mywallet.data.repo

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
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
import org.aristonis.mywallet.domain.port.AccountEntryReader
import org.aristonis.mywallet.domain.port.EntryKey
import org.aristonis.mywallet.domain.port.EntryRepository
import java.time.LocalDate

/**
 * Stores each entry as a header plus legs, and reads them back through the kind's recipe, so a row
 * no recipe can read surfaces as [WalletException.UnreadableRow] naming it.
 *
 * Every write touches two tables, so each runs in its own Room transaction (joining the caller's
 * when there is one): a header never lands without its legs.
 */
class RoomEntryRepository(
    private val database: WalletDatabase,
    private val dao: EntryDao,
) : EntryRepository, AccountEntryReader {

    /**
     * [observePage] narrowed to the entries with a leg on [accountId]. The filter runs in
     * [EntryDao.readAccountPage], so the limit counts the account's entries only.
     */
    override fun observeAccountPage(
        accountId: Long,
        range: DateRange,
        after: EntryKey?,
        limit: Int,
    ): Flow<List<Transaction>> {
        val (upper, beforeId) = pageBounds(range, after, limit)
        return database.invalidationTracker.createFlow(*ENTRY_READ_TABLES).conflate().map {
            dao.readAccountPage(accountId, range.start, upper, beforeId, limit).toTransactions()
        }
    }

    override fun observeAll(): Flow<List<Transaction>> = observeBetween(DateRange.ALL_TIME)

    override suspend fun entryIdsOnDebt(debtId: Long, excludingKinds: Set<String>): List<Long> =
        if (excludingKinds.isEmpty()) dao.allEntryIdsOnDebt(debtId)
        else dao.entryIdsOnDebt(debtId, excludingKinds.toList())

    /** Filters through the debt index while reconstructing complete entries in one read transaction. */
    override fun observeOnDebt(debtId: Long): Flow<List<Transaction>> =
        database.invalidationTracker.createFlow(*ENTRY_READ_TABLES).conflate().map {
            dao.readOnDebt(debtId).toTransactions()
        }

    /**
     * Re-reads the range whenever entries, legs or debts change. This is what Room builds for an
     * observable query (conflated, so a burst of writes costs one read), around a read made of two
     * statements in one transaction: [EntryDao.readBetween].
     */
    override fun observeBetween(range: DateRange): Flow<List<Transaction>> =
        database.invalidationTracker.createFlow(*ENTRY_READ_TABLES).conflate().map {
            dao.readBetween(range.start, range.endInclusive).toTransactions()
        }

    /** One keyset page, re-read on the same tables as [observeBetween]. */
    override fun observePage(range: DateRange, after: EntryKey?, limit: Int): Flow<List<Transaction>> {
        val (upper, beforeId) = pageBounds(range, after, limit)
        return database.invalidationTracker.createFlow(*ENTRY_READ_TABLES).conflate().map {
            dao.readPage(range.start, upper, beforeId, limit).toTransactions()
        }
    }

    /**
     * The upper date bound and the id a keyset page starts from. A key dated after the range starts
     * at the range's end with nothing excluded, and a key inside it starts at its own date, past the
     * ids already shown.
     */
    private fun pageBounds(range: DateRange, after: EntryKey?, limit: Int): Pair<LocalDate, Long> {
        require(limit > 0) { "a page holds at least one entry, but the limit was $limit" }
        val inside = after?.takeIf { it.date <= range.endInclusive }
        return (inside?.date ?: range.endInclusive) to (inside?.id ?: Long.MAX_VALUE)
    }

    override suspend fun findById(id: Long): Transaction? = database.withTransaction {
        val rows = dao.findById(id)
        if (rows.isEmpty()) return@withTransaction null
        rows.toTransactions(dao.deductionLinesForEntry(id)).singleOrNull()
    }

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
            if (stored.deductionLines.isNotEmpty()) {
                database.deductionDao().insertLines(stored.deductionLines.map { it.copy(entryId = id, id = 0) })
            }
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
     * The entry's deduction lines are replaced with the snapshots carried by [entry], in the same
     * transaction as its header and legs.
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
            if (stored.deductionLines.isNotEmpty()) {
                database.deductionDao().insertLines(stored.deductionLines.map { it.copy(entryId = entry.id, id = 0) })
            }
        }
    }

    override suspend fun delete(id: Long) = dao.deleteById(id)

    private companion object {
        /** The tables a range read touches; a change to any of them re-runs it. */
        val ENTRY_READ_TABLES = arrayOf("entries", "entry_legs", "debts", "income_deduction_lines")
    }
}
