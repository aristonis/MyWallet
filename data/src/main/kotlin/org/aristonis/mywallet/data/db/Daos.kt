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

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun findById(id: Long): TransactionEntity?

    @Insert
    suspend fun insert(transaction: TransactionEntity): Long

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM transactions")
    suspend fun getAll(): List<TransactionEntity>

    @Insert
    suspend fun insertAll(rows: List<TransactionEntity>)

    @Query("DELETE FROM transactions")
    suspend fun clear()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun findById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories")
    suspend fun getAll(): List<CategoryEntity>

    @Insert
    suspend fun insertAll(rows: List<CategoryEntity>)

    @Query("DELETE FROM categories")
    suspend fun clear()
}

@Dao
interface CurrencyDao {
    @Query("SELECT * FROM currencies ORDER BY code")
    fun observeAll(): Flow<List<CurrencyEntity>>

    @Query("SELECT * FROM currencies WHERE code = :code")
    suspend fun findByCode(code: String): CurrencyEntity?

    @Query("SELECT * FROM currencies")
    suspend fun getAll(): List<CurrencyEntity>

    @Insert
    suspend fun insertAll(rows: List<CurrencyEntity>)

    @Query("DELETE FROM currencies")
    suspend fun clear()
}

@Dao
interface RateDao {
    @Query("SELECT * FROM rates ORDER BY currencyCode")
    fun observeAll(): Flow<List<RateEntity>>

    @Query("SELECT * FROM rates WHERE currencyCode = :code")
    suspend fun findByCode(code: String): RateEntity?

    @Upsert
    suspend fun upsert(rate: RateEntity)

    @Query("SELECT * FROM rates")
    suspend fun getAll(): List<RateEntity>

    @Insert
    suspend fun insertAll(rows: List<RateEntity>)

    @Query("DELETE FROM rates")
    suspend fun clear()
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = $SETTINGS_ROW_ID")
    fun observe(): Flow<SettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = $SETTINGS_ROW_ID")
    suspend fun get(): SettingsEntity?

    @Upsert
    suspend fun upsert(settings: SettingsEntity)

    @Query("DELETE FROM settings")
    suspend fun clear()
}
