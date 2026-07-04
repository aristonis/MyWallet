package org.aristonis.mywallet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room generates the implementation of this interface from the annotations. A `@Query` returning
 * `Flow` re-emits automatically whenever the `accounts` table changes — that is what makes the
 * whole app reactive end-to-end.
 */
@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun findById(id: Long): AccountEntity?

    @Upsert
    suspend fun upsert(account: AccountEntity): Long

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: Long)

    // --- Backup: one-shot snapshot + bulk replace (used by the backup/restore path) ---

    @Query("SELECT * FROM accounts")
    suspend fun getAll(): List<AccountEntity>

    @Insert
    suspend fun insertAll(rows: List<AccountEntity>)

    @Query("DELETE FROM accounts")
    suspend fun clear()
}
