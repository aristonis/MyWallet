package org.aristonis.mywallet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * The debts table. The app reads and writes single rows through `RoomDebtRepository`; backup reads
 * it as a one-shot snapshot and replaces it in bulk.
 */
@Dao
interface DebtDao {
    @Query("SELECT * FROM debts ORDER BY id")
    suspend fun getAll(): List<DebtEntity>

    @Query("SELECT * FROM debts ORDER BY id")
    fun observeAll(): Flow<List<DebtEntity>>

    @Query("SELECT * FROM debts WHERE id = :id")
    suspend fun findById(id: Long): DebtEntity?

    /** The one row the unique index allows for this side, currency and key, or null. */
    @Query("SELECT * FROM debts WHERE side = :side AND currencyCode = :currencyCode AND counterpartyKey = :counterpartyKey")
    suspend fun findByKey(side: String, currencyCode: String, counterpartyKey: String): DebtEntity?

    /**
     * A plain insert: a clash on the unique index fails loudly instead of being skipped, so a second
     * row for one person can never be hidden behind a silent no-op.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: DebtEntity): Long

    @Insert
    suspend fun insertAll(rows: List<DebtEntity>)

    /** Returns how many rows changed: 0 when no debt has [id]. */
    @Query("UPDATE debts SET dueDate = :dueDate, note = :note WHERE id = :id")
    suspend fun updateDetails(id: Long, dueDate: LocalDate?, note: String?): Int

    /** Aborts on the unique index when another row of the same side and currency holds the key. */
    @Query("UPDATE debts SET counterparty = :counterparty, counterpartyKey = :counterpartyKey WHERE id = :id")
    suspend fun rename(id: Long, counterparty: String, counterpartyKey: String): Int

    /**
     * One stored name per key whose key starts with [escapedPrefix], in key order. The name shown is
     * the oldest row's, so the same key always gives the same spelling whichever side or currency
     * also holds it.
     *
     * [escapedPrefix] must already have `\`, `%` and `_` escaped with a backslash (see
     * [escapeLikePrefix]); unescaped, a typed "5%" would match every name starting with 5. An empty
     * prefix matches every row. No index covers a LIKE on the key alone, so this scans the table,
     * which holds one row per person, side and currency.
     */
    @Query(
        "SELECT counterparty FROM debts WHERE id IN (" +
            "SELECT MIN(id) FROM debts WHERE counterpartyKey LIKE :escapedPrefix || '%' ESCAPE '\\' " +
            "GROUP BY counterpartyKey" +
            ") ORDER BY counterpartyKey",
    )
    suspend fun counterpartyNames(escapedPrefix: String): List<String>

    /** Fails while any leg still points at a debt; clear the entries first. */
    @Query("DELETE FROM debts")
    suspend fun clear()
}

/** [prefix] with the LIKE escape character and both wildcards escaped, for [DebtDao.counterpartyNames]. */
internal fun escapeLikePrefix(prefix: String): String = buildString(prefix.length) {
    for (c in prefix) {
        if (c == LIKE_ESCAPE || c == '%' || c == '_') append(LIKE_ESCAPE)
        append(c)
    }
}

private const val LIKE_ESCAPE = '\\'
