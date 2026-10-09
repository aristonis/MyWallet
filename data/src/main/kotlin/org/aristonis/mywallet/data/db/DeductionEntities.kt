package org.aristonis.mywallet.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A deduction the owner set up once (tax, insurance) and applies to incomes. [nameKey] is the
 * trimmed, case-folded name; its unique index keeps two items from ever mapping to one "I owe" debt.
 * [value] is exact decimal text, and [valueCurrencyCode] is set only for a fixed amount.
 */
@Entity(
    tableName = "deduction_items",
    indices = [Index(value = ["nameKey"], unique = true)],
)
data class DeductionItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val nameKey: String,
    val mode: String,
    val value: String,
    val valueCurrencyCode: String? = null,
    val timing: String,
    val expenseCategoryId: Long,
    val enabled: Boolean,
    val sortOrder: Int,
)

/**
 * The income categories that add a deduction item to a new income by default. Removing either the
 * item or the category removes the link with it.
 */
@Entity(
    tableName = "deduction_item_auto_categories",
    primaryKeys = ["itemId", "incomeCategoryId"],
    foreignKeys = [
        ForeignKey(
            entity = DeductionItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["incomeCategoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["incomeCategoryId"])],
)
data class DeductionItemAutoCategoryEntity(
    val itemId: Long,
    val incomeCategoryId: Long,
)

/**
 * A snapshot of one deduction as it was applied to one income, so later edits of the item never
 * change a past entry. The deducted amount and its category live on the line's legs.
 *
 * Lines go with their entry; an item cannot be deleted while any line still uses it.
 */
@Entity(
    tableName = "income_deduction_lines",
    foreignKeys = [
        ForeignKey(
            entity = EntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DeductionItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["entryId", "position"], unique = true),
        Index(value = ["itemId"]),
    ],
)
data class IncomeDeductionLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    val itemId: Long,
    val position: Int,
    val name: String,
    val mode: String,
    val value: String,
    val valueCurrencyCode: String? = null,
    val timing: String,
)
