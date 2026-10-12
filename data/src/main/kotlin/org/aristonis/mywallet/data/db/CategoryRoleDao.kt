package org.aristonis.mywallet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

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

    /** Keeps valid bindings and atomically replaces only a missing or invalid restored binding. */
    @Transaction
    suspend fun bindIfMissing(role: String, kind: String, categoryId: Long): Boolean {
        if (categoryFor(role, kind) != null) return false
        require(isTopLevelOfKind(categoryId, kind)) { "role category must be top-level and match its kind" }
        // Invalid restored bindings are missing for reads and must also be missing for creation.
        deleteInvalidBinding(role, kind)
        return insertIfAbsent(CategoryRoleEntity(role, categoryId)) != -1L
    }

    @Query(
        "SELECT EXISTS(SELECT 1 FROM categories WHERE id = :categoryId AND kind = :kind " +
            "AND parentId = $TOP_LEVEL_PARENT_ID)",
    )
    suspend fun isTopLevelOfKind(categoryId: Long, kind: String): Boolean

    @Query(
        "DELETE FROM category_roles WHERE role = :role AND NOT EXISTS " +
            "(SELECT 1 FROM categories c WHERE c.id = category_roles.categoryId AND c.kind = :kind " +
            "AND c.parentId = $TOP_LEVEL_PARENT_ID)",
    )
    suspend fun deleteInvalidBinding(role: String, kind: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: CategoryRoleEntity): Long

    @Insert
    suspend fun insertAll(rows: List<CategoryRoleEntity>)

    @Query("DELETE FROM category_roles")
    suspend fun clear()
}
