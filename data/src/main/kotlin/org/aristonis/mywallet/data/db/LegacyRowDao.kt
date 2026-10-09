package org.aristonis.mywallet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Raw copies of old rows the upgrade could not carry over in full. Only the upgrade and a restore
 * write them, and nothing in the app edits them, so backup is the only reader.
 */
@Dao
interface LegacyRowDao {
    @Query("SELECT * FROM legacy_unreadable_rows ORDER BY id")
    suspend fun getAll(): List<LegacyUnreadableRowEntity>

    @Insert
    suspend fun insertAll(rows: List<LegacyUnreadableRowEntity>)

    @Query("DELETE FROM legacy_unreadable_rows")
    suspend fun clear()
}
