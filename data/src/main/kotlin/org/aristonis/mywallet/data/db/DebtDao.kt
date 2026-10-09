package org.aristonis.mywallet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** The debts table. So far only backup reads and writes it: a one-shot snapshot and a bulk replace. */
@Dao
interface DebtDao {
    @Query("SELECT * FROM debts ORDER BY id")
    suspend fun getAll(): List<DebtEntity>

    @Insert
    suspend fun insertAll(rows: List<DebtEntity>)

    /** Fails while any leg still points at a debt; clear the entries first. */
    @Query("DELETE FROM debts")
    suspend fun clear()
}
