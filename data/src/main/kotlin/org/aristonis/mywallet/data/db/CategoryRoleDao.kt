package org.aristonis.mywallet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Which category each built-in role files its legs under. */
@Dao
interface CategoryRoleDao {
    @Query("SELECT * FROM category_roles ORDER BY role")
    suspend fun getAll(): List<CategoryRoleEntity>

    /**
     * The category bound to [role], or null when the binding does not fit: a restore writes bindings
     * verbatim, so one may point at a category of another [kind] or at a sub-category.
     */
    @Query(
        "SELECT r.categoryId FROM category_roles r JOIN categories c ON c.id = r.categoryId " +
            "WHERE r.role = :role AND c.kind = :kind AND c.parentId = $TOP_LEVEL_PARENT_ID",
    )
    suspend fun categoryFor(role: String, kind: String): Long?

    @Insert
    suspend fun insertAll(rows: List<CategoryRoleEntity>)

    @Query("DELETE FROM category_roles")
    suspend fun clear()
}
