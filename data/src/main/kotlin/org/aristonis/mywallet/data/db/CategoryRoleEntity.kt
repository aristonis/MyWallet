package org.aristonis.mywallet.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Which category a built-in role (interest, fees, a lost or forgiven debt) files its legs under. One
 * row per role; deleting the category drops the binding, and the role falls back to the bucket.
 */
@Entity(
    tableName = "category_roles",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["categoryId"])],
)
data class CategoryRoleEntity(
    @PrimaryKey val role: String,
    val categoryId: Long,
)
