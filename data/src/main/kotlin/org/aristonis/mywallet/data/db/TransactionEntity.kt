package org.aristonis.mywallet.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * Single-table storage for all three transaction kinds (single-table inheritance): a [type]
 * discriminator + shared "primary" columns, plus "secondary" columns used only by transfers.
 * The mapper reconstructs the correct sealed [org.aristonis.mywallet.domain.model.Transaction].
 *
 * [subCategoryId] carries a foreign key that clears itself when its category goes away. A dangling
 * sub-category reference is worse than none: the row still renders, but opening it in the editor
 * hits the validation that a sub-category must belong to the chosen category, and the user cannot
 * save the transaction again. [categoryId] deliberately has no such constraint — an income or
 * expense must always name a category, so a deleted one is reassigned to the fallback bucket rather
 * than nulled.
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["subCategoryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["subCategoryId"])],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, // INCOME | EXPENSE | TRANSFER
    val date: LocalDate,
    val note: String? = null,
    // primary leg: income target / expense source / transfer source
    val primaryAccountId: Long,
    val primaryAmount: String,
    val primaryCurrency: String,
    // income / expense only
    val categoryId: Long? = null,
    val subCategoryId: Long? = null,
    // transfer only: destination leg + applied rate
    val secondaryAccountId: Long? = null,
    val secondaryAmount: String? = null,
    val secondaryCurrency: String? = null,
    val rateUsed: String? = null,
)
