package org.aristonis.mywallet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY date DESC, id DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Insert
    suspend fun insert(transaction: TransactionEntity): Long

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun findById(id: Long): CategoryEntity?
}

@Dao
interface CurrencyDao {
    @Query("SELECT * FROM currencies ORDER BY code")
    fun observeAll(): Flow<List<CurrencyEntity>>

    @Query("SELECT * FROM currencies WHERE code = :code")
    suspend fun findByCode(code: String): CurrencyEntity?
}

@Dao
interface RateDao {
    @Query("SELECT * FROM rates ORDER BY currencyCode")
    fun observeAll(): Flow<List<RateEntity>>

    @Query("SELECT * FROM rates WHERE currencyCode = :code")
    suspend fun findByCode(code: String): RateEntity?

    @Upsert
    suspend fun upsert(rate: RateEntity)
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = $SETTINGS_ROW_ID")
    fun observe(): Flow<SettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = $SETTINGS_ROW_ID")
    suspend fun get(): SettingsEntity?

    @Upsert
    suspend fun upsert(settings: SettingsEntity)
}
