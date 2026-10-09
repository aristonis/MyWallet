package org.aristonis.mywallet.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One signed amount of an entry on exactly one of [accountId], [debtId] or [categoryId]. The amount
 * and rate are exact decimal text, so nothing is rounded on the way through storage.
 *
 * [accountId] and [categoryId] carry no foreign key, as in the old transactions table: a dirty
 * legacy id would fail the migration's key check and block the launch. [debtId] is restricted, so a
 * debt with history cannot be deleted; [subCategoryId] clears itself when its category goes.
 *
 * The `(accountId, amount)` and `(debtId, amount)` indexes cover the balance sums, and still lead
 * with the key column so they serve the joins too. The `(entryId, position)` index hands an entry's
 * legs to the entry reads already in position order (the rowid, last in every index entry, breaks a
 * tie by id), so reading a date range never needs a temporary sort.
 */
@Entity(
    tableName = "entry_legs",
    foreignKeys = [
        ForeignKey(
            entity = EntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DebtEntity::class,
            parentColumns = ["id"],
            childColumns = ["debtId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["subCategoryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["entryId", "role", "position"], unique = true),
        Index(value = ["entryId", "position"]),
        Index(value = ["accountId", "amount"]),
        Index(value = ["debtId", "amount"]),
        Index(value = ["categoryId"]),
        Index(value = ["subCategoryId"]),
    ],
)
data class EntryLegEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    val role: String,
    val position: Int,
    val accountId: Long? = null,
    val debtId: Long? = null,
    val categoryId: Long? = null,
    val subCategoryId: Long? = null,
    val amount: String,
    val currencyCode: String,
    val rateToBase: String? = null,
)
