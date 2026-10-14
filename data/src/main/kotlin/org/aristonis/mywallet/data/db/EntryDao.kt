package org.aristonis.mywallet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * One leg of a stored entry with its header beside it, as the entry reads return them. An entry
 * with no legs (an old row the upgrade could not read) comes back as one row whose leg columns are
 * all null. [debtSide] is the side of the debt a debt leg points at, read in the same query so a leg
 * and its debt can never be seen from two different moments.
 */
data class EntryLegRow(
    val entryId: Long,
    val kind: String,
    val date: LocalDate,
    val note: String?,
    val pairRate: String?,
    val legId: Long?,
    val role: String?,
    val position: Int?,
    val accountId: Long?,
    val debtId: Long?,
    val categoryId: Long?,
    val subCategoryId: Long?,
    val amount: String?,
    val currencyCode: String?,
    val rateToBase: String?,
    val debtSide: String?,
)

/**
 * One leg of an entry in a range read, with the id of its entry and the side of the debt it points
 * at. The header is read once on its own row of [EntryRangeRows.headers], not repeated beside every leg.
 */
data class LegRow(
    val entryId: Long,
    val legId: Long,
    val role: String,
    val position: Int,
    val accountId: Long?,
    val debtId: Long?,
    val categoryId: Long?,
    val subCategoryId: Long?,
    val amount: String,
    val currencyCode: String,
    val rateToBase: String?,
    val debtSide: String?,
)

/**
 * The headers of the entries in a range and the legs of those same entries, read inside one
 * transaction so the two lists are one moment of the database. Both are newest entry first (date
 * desc, then id desc) and each entry's legs by position then id, so they can be walked side by side.
 * An entry with no legs has a header and no leg rows.
 */
data class EntryRangeRows(
    val headers: List<EntryEntity>,
    val legs: List<LegRow>,
    val deductionLines: List<IncomeDeductionLineEntity> = emptyList(),
)

/**
 * Every header with its legs and the side of any debt they point at. One statement, so one Flow
 * re-reads all of it whenever any of the three tables changes, and a read never mixes a header with
 * legs from before or after an edit.
 */
private const val ENTRY_ROWS_SQL =
    "SELECT e.id AS entryId, e.kind AS kind, e.date AS date, e.note AS note, e.pairRate AS pairRate, " +
        "l.id AS legId, l.role AS role, l.position AS position, l.accountId AS accountId, l.debtId AS debtId, " +
        "l.categoryId AS categoryId, l.subCategoryId AS subCategoryId, l.amount AS amount, " +
        "l.currencyCode AS currencyCode, l.rateToBase AS rateToBase, d.side AS debtSide " +
        "FROM entries AS e LEFT JOIN entry_legs AS l ON l.entryId = e.id LEFT JOIN debts AS d ON d.id = l.debtId"

/** The debt index finds matching entry ids before any headers or sibling legs are read. */
private const val DEBT_ENTRY_IDS_SQL = "SELECT entryId FROM entry_legs WHERE debtId = :debtId"

/**
 * The ids of the entries with a leg on the account, read off the account index. `IN` keeps each
 * entry once, however many of its legs are on the account.
 */
private const val ACCOUNT_ENTRY_IDS_SQL = "SELECT entryId FROM entry_legs WHERE accountId = :accountId"

/**
 * How many entry ids one `IN (...)` list binds. Older Android ships SQLite builds that refuse more
 * than 999 bound variables in a statement, so a page larger than that is read in several lists.
 */
private const val ENTRY_ID_CHUNK = 500

@Dao
interface EntryDao {

    @Query(
        "SELECT id, kind, date, note, pairRate FROM entries WHERE id IN ($DEBT_ENTRY_IDS_SQL) " +
            "ORDER BY date DESC, id DESC",
    )
    suspend fun headersOnDebt(debtId: Long): List<EntryEntity>

    @Query("SELECT id FROM entries WHERE id IN ($DEBT_ENTRY_IDS_SQL) ORDER BY date DESC, id DESC")
    suspend fun allEntryIdsOnDebt(debtId: Long): List<Long>

    /** Header exclusions preserve income ids without asking a recipe to reconstruct their legs. */
    @Query(
        "SELECT id FROM entries WHERE id IN ($DEBT_ENTRY_IDS_SQL) AND kind NOT IN (:excludingKinds) " +
            "ORDER BY date DESC, id DESC",
    )
    suspend fun entryIdsOnDebt(debtId: Long, excludingKinds: List<String>): List<Long>

    /** Reads every sibling leg of matching entries, so an account leg on a repayment is retained. */
    @Query(
        "SELECT l.entryId AS entryId, l.id AS legId, l.role AS role, l.position AS position, " +
            "l.accountId AS accountId, l.debtId AS debtId, l.categoryId AS categoryId, " +
            "l.subCategoryId AS subCategoryId, l.amount AS amount, l.currencyCode AS currencyCode, " +
            "l.rateToBase AS rateToBase, d.side AS debtSide " +
            "FROM entries AS e CROSS JOIN entry_legs AS l ON l.entryId = e.id " +
            "LEFT JOIN debts AS d ON d.id = l.debtId " +
            "WHERE e.id IN ($DEBT_ENTRY_IDS_SQL) ORDER BY e.date DESC, e.id DESC, l.position, l.id",
    )
    suspend fun legsOnDebt(debtId: Long): List<LegRow>

    @Query(
        "SELECT * FROM income_deduction_lines WHERE entryId IN " +
            "(SELECT DISTINCT entryId FROM entry_legs WHERE debtId = :debtId) ORDER BY entryId, position",
    )
    suspend fun deductionLinesOnDebt(debtId: Long): List<IncomeDeductionLineEntity>

    /** Headers and complete legs share a snapshot even when an entry changes debt while reading. */
    @Transaction
    suspend fun readOnDebt(debtId: Long): EntryRangeRows =
        EntryRangeRows(
            headers = headersOnDebt(debtId),
            legs = legsOnDebt(debtId),
            deductionLines = deductionLinesOnDebt(debtId),
        )

    /**
     * Entries dated inside the inclusive [start]..[end], newest first (date desc, then id desc), each
     * entry's legs by position then id. The date index serves the filter and the entry order (`id`
     * is the rowid, so same-day entries come off it already ordered), and the `(entryId, position)`
     * index the leg order, so nothing is sorted afterwards.
     *
     * Every leg row repeats its header here. The repository reads ranges through [readBetween],
     * which sends each header once.
     */
    @Query(
        "$ENTRY_ROWS_SQL WHERE e.date BETWEEN :start AND :end " +
            "ORDER BY e.date DESC, e.id DESC, l.position, l.id",
    )
    fun observeBetween(start: LocalDate, end: LocalDate): Flow<List<EntryLegRow>>

    /**
     * The headers of the entries dated inside the inclusive [start]..[end], newest first. The date
     * index serves both the filter and the order (`id` is the rowid, so it rides in the index).
     */
    @Query(
        "SELECT id, kind, date, note, pairRate FROM entries WHERE date BETWEEN :start AND :end " +
            "ORDER BY date DESC, id DESC",
    )
    suspend fun headersBetween(start: LocalDate, end: LocalDate): List<EntryEntity>

    /**
     * The legs of the entries [headersBetween] returns for the same dates, in the same entry order,
     * each entry's legs by position then id, with the side of any debt they point at.
     *
     * `CROSS JOIN` keeps `entries` as the outer loop in SQLite, so the date index walks the entries
     * in order and the `(entryId, position)` index hands each one its legs already sorted: no
     * temporary sort, whatever the planner would otherwise guess for an inner join.
     */
    @Query(
        "SELECT l.entryId AS entryId, l.id AS legId, l.role AS role, l.position AS position, " +
            "l.accountId AS accountId, l.debtId AS debtId, l.categoryId AS categoryId, " +
            "l.subCategoryId AS subCategoryId, l.amount AS amount, l.currencyCode AS currencyCode, " +
            "l.rateToBase AS rateToBase, d.side AS debtSide " +
            "FROM entries AS e CROSS JOIN entry_legs AS l ON l.entryId = e.id " +
            "LEFT JOIN debts AS d ON d.id = l.debtId " +
            "WHERE e.date BETWEEN :start AND :end ORDER BY e.date DESC, e.id DESC, l.position, l.id",
    )
    suspend fun legsBetween(start: LocalDate, end: LocalDate): List<LegRow>

    @Query(
        "SELECT l.* FROM income_deduction_lines l JOIN entries e ON e.id = l.entryId " +
            "WHERE e.date BETWEEN :start AND :end ORDER BY e.date DESC, e.id DESC, l.position",
    )
    suspend fun deductionLinesBetween(start: LocalDate, end: LocalDate): List<IncomeDeductionLineEntity>

    /**
     * [headersBetween] and [legsBetween] as one snapshot. The header columns come once per entry
     * instead of once per leg, which keeps the leg rows narrow enough to be read in far fewer
     * cursor window fills than the one-statement join of [observeBetween].
     */
    @Transaction
    suspend fun readBetween(start: LocalDate, end: LocalDate): EntryRangeRows =
        EntryRangeRows(
            headers = headersBetween(start, end),
            legs = legsBetween(start, end),
            deductionLines = deductionLinesBetween(start, end),
        )

    /**
     * At most [limit] headers dated [start]..[upper], newest first, that come strictly after the key
     * ([upper], [beforeId]) in that order. The first page passes the range's end as [upper] and
     * [Long.MAX_VALUE] as [beforeId], so the key excludes nothing.
     *
     * Since every row is dated at most [upper], "after the key" is `date < upper OR id < beforeId`.
     * Both terms carry a unary `+` so SQLite cannot answer the OR from two indexes and sort the union:
     * the date index walks backwards from [upper], which is already the order asked for, and the id
     * rides in that index, so a page costs its own rows however deep it starts. No OFFSET, which
     * would walk every row it skips.
     */
    @Query(
        "SELECT id, kind, date, note, pairRate FROM entries " +
            "WHERE date BETWEEN :start AND :upper AND (+date < :upper OR +id < :beforeId) " +
            "ORDER BY date DESC, id DESC LIMIT :limit",
    )
    suspend fun headersPage(start: LocalDate, upper: LocalDate, beforeId: Long, limit: Int): List<EntryEntity>

    /**
     * The legs of the entries from the key ([upper], [beforeId]), exclusive, down to the entry
     * ([lowest], [lowestId]), inclusive: the entries one [headersPage] returned, when read in the
     * same transaction. Same order and same plan as [legsBetween].
     */
    @Query(
        "SELECT l.entryId AS entryId, l.id AS legId, l.role AS role, l.position AS position, " +
            "l.accountId AS accountId, l.debtId AS debtId, l.categoryId AS categoryId, " +
            "l.subCategoryId AS subCategoryId, l.amount AS amount, l.currencyCode AS currencyCode, " +
            "l.rateToBase AS rateToBase, d.side AS debtSide " +
            "FROM entries AS e CROSS JOIN entry_legs AS l ON l.entryId = e.id " +
            "LEFT JOIN debts AS d ON d.id = l.debtId " +
            "WHERE e.date BETWEEN :lowest AND :upper AND (+e.date < :upper OR +e.id < :beforeId) " +
            "AND (+e.date > :lowest OR +e.id >= :lowestId) " +
            "ORDER BY e.date DESC, e.id DESC, l.position, l.id",
    )
    suspend fun legsOfPage(
        upper: LocalDate,
        beforeId: Long,
        lowest: LocalDate,
        lowestId: Long,
    ): List<LegRow>

    @Query("SELECT l.* FROM income_deduction_lines l JOIN entries e ON e.id = l.entryId " +
        "WHERE e.date BETWEEN :lowest AND :upper AND (+e.date < :upper OR +e.id < :beforeId) " +
        "AND (+e.date > :lowest OR +e.id >= :lowestId) ORDER BY e.date DESC, e.id DESC, l.position")
    suspend fun deductionLinesOfPage(
        upper: LocalDate,
        beforeId: Long,
        lowest: LocalDate,
        lowestId: Long,
    ): List<IncomeDeductionLineEntity>

    /**
     * [headersPage] and the legs of exactly those entries as one snapshot, so a write between the two
     * statements cannot hand a header someone else's legs.
     */
    @Transaction
    suspend fun readPage(start: LocalDate, upper: LocalDate, beforeId: Long, limit: Int): EntryRangeRows {
        val headers = headersPage(start, upper, beforeId, limit)
        val last = headers.lastOrNull() ?: return EntryRangeRows(headers = emptyList(), legs = emptyList())
        return EntryRangeRows(
            headers = headers,
            legs = legsOfPage(upper, beforeId, last.date, last.id),
            deductionLines = deductionLinesOfPage(upper, beforeId, last.date, last.id),
        )
    }

    /**
     * [headersPage] for the entries with a leg on [accountId]: same key, same order, same limit.
     *
     * The account index builds the list of matching ids once, and the date index then walks the
     * entries newest first, keeping those on the list, so the limit counts matches only and no
     * sort is needed. The `+` on `id IN` keeps SQLite from looking the entries up by id from that
     * list instead, which would lose the date order and sort every match before the limit.
     */
    @Query(
        "SELECT id, kind, date, note, pairRate FROM entries " +
            "WHERE +id IN ($ACCOUNT_ENTRY_IDS_SQL) " +
            "AND date BETWEEN :start AND :upper AND (+date < :upper OR +id < :beforeId) " +
            "ORDER BY date DESC, id DESC LIMIT :limit",
    )
    suspend fun accountHeadersPage(
        accountId: Long,
        start: LocalDate,
        upper: LocalDate,
        beforeId: Long,
        limit: Int,
    ): List<EntryEntity>

    /**
     * Every leg, not only the account's, of the entries with these ids, with the side of any debt
     * they point at. Each id is a lookup on the `(entryId, position)` index, so the read costs the
     * listed entries' own legs. No order: [readAccountPage] puts the rows in page order itself.
     */
    @Query(
        "SELECT l.entryId AS entryId, l.id AS legId, l.role AS role, l.position AS position, " +
            "l.accountId AS accountId, l.debtId AS debtId, l.categoryId AS categoryId, " +
            "l.subCategoryId AS subCategoryId, l.amount AS amount, l.currencyCode AS currencyCode, " +
            "l.rateToBase AS rateToBase, d.side AS debtSide " +
            "FROM entry_legs AS l LEFT JOIN debts AS d ON d.id = l.debtId " +
            "WHERE l.entryId IN (:entryIds)",
    )
    suspend fun legsOfEntries(entryIds: List<Long>): List<LegRow>

    /** The deduction lines of the entries with these ids, off the `(entryId, position)` index, unordered. */
    @Query("SELECT * FROM income_deduction_lines WHERE entryId IN (:entryIds)")
    suspend fun deductionLinesOfEntries(entryIds: List<Long>): List<IncomeDeductionLineEntity>

    /**
     * [accountHeadersPage] and the complete legs and deduction lines of exactly those entries as one
     * snapshot, like [readPage].
     *
     * The header read builds the account's id list once per page, so that statement grows with the
     * number of legs on the account. The legs and lines are then read by the page's own entry ids
     * and cost those entries only, then sorted here into the order [EntryRangeRows] promises: by
     * entry in header order, legs by position then id, lines by position.
     */
    @Transaction
    suspend fun readAccountPage(
        accountId: Long,
        start: LocalDate,
        upper: LocalDate,
        beforeId: Long,
        limit: Int,
    ): EntryRangeRows {
        val headers = accountHeadersPage(accountId, start, upper, beforeId, limit)
        if (headers.isEmpty()) return EntryRangeRows(headers = emptyList(), legs = emptyList())
        val chunks = headers.map { it.id }.chunked(ENTRY_ID_CHUNK)
        val rankById = headers.withIndex().associate { (index, header) -> header.id to index }
        return EntryRangeRows(
            headers = headers,
            legs = chunks.flatMap { legsOfEntries(it) }.sortedWith(
                compareBy<LegRow>({ rankById.getValue(it.entryId) }, { it.position }, { it.legId }),
            ),
            deductionLines = chunks.flatMap { deductionLinesOfEntries(it) }.sortedWith(
                compareBy<IncomeDeductionLineEntity>({ rankById.getValue(it.entryId) }, { it.position }),
            ),
        )
    }

    /** The rows of the entry with [id], its legs by position then id; empty when there is none. */
    @Query("$ENTRY_ROWS_SQL WHERE e.id = :id ORDER BY l.position, l.id")
    suspend fun findById(id: Long): List<EntryLegRow>

    @Query("SELECT * FROM income_deduction_lines WHERE entryId = :id ORDER BY position")
    suspend fun deductionLinesForEntry(id: Long): List<IncomeDeductionLineEntity>

    /**
     * A plain insert: id 0 gets a fresh id, any other id is kept. A taken id fails the primary key
     * and aborts, so an undo can never overwrite the entry that now holds that id.
     */
    @Insert
    suspend fun insertEntry(entry: EntryEntity): Long

    @Insert
    suspend fun insertLegs(legs: List<EntryLegEntity>)

    @Query("SELECT EXISTS (SELECT 1 FROM entries WHERE id = :id)")
    suspend fun exists(id: Long): Boolean

    /** Returns how many headers matched: 0 means the entry is gone. */
    @Update
    suspend fun updateEntry(entry: EntryEntity): Int

    @Query("DELETE FROM entry_legs WHERE entryId = :entryId")
    suspend fun deleteLegsOf(entryId: Long)

    @Query("DELETE FROM income_deduction_lines WHERE entryId = :entryId")
    suspend fun deleteDeductionLinesOf(entryId: Long)

    /** Removes the header; its legs and deduction lines go with it through their cascading keys. */
    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    // --- Backup: one-shot snapshot + bulk replace ---

    @Query("SELECT * FROM entries ORDER BY id")
    suspend fun getAll(): List<EntryEntity>

    /** In id order, so a restore that inserts them in this order numbers them the same way. */
    @Query("SELECT * FROM entry_legs ORDER BY id")
    suspend fun getAllLegs(): List<EntryLegEntity>

    @Insert
    suspend fun insertAll(rows: List<EntryEntity>)

    /** Clears every header; legs and deduction lines go with them. */
    @Query("DELETE FROM entries")
    suspend fun clear()
}
