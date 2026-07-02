package org.aristonis.mywallet.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * Single-table storage for all three transaction kinds (single-table inheritance): a [type]
 * discriminator + shared "primary" columns, plus "secondary" columns used only by transfers.
 * The mapper reconstructs the correct sealed [org.aristonis.mywallet.domain.model.Transaction].
 */
@Entity(tableName = "transactions")
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
