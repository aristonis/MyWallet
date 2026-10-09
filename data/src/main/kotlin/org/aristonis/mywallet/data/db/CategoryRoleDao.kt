package org.aristonis.mywallet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Which category each built-in role files its legs under. */
@Dao
interface CategoryRoleDao {
    @Query("SELECT * FROM category_roles ORDER BY role")
    suspend fun getAll(): List<CategoryRoleEntity>

    @Insert
    suspend fun insertAll(rows: List<CategoryRoleEntity>)

    @Query("DELETE FROM category_roles")
    suspend fun clear()
}
