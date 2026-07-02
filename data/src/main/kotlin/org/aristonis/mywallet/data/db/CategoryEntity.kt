package org.aristonis.mywallet.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String, // INCOME | EXPENSE
    val parentId: Long? = null,
)
