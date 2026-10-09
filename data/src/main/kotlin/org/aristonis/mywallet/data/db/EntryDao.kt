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

@Dao
interface EntryDao {

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

    /**
     * [headersBetween] and [legsBetween] as one snapshot. The header columns come once per entry
     * instead of once per leg, which keeps the leg rows narrow enough to be read in far fewer
     * cursor window fills than the one-statement join of [observeBetween].
     */
    @Transaction
    suspend fun readBetween(start: LocalDate, end: LocalDate): EntryRangeRows =
        EntryRangeRows(headers = headersBetween(start, end), legs = legsBetween(start, end))

    /** The rows of the entry with [id], its legs by position then id; empty when there is none. */
    @Query("$ENTRY_ROWS_SQL WHERE e.id = :id ORDER BY l.position, l.id")
    suspend fun findById(id: Long): List<EntryLegRow>

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
