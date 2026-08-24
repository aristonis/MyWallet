package org.aristonis.mywallet.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The `parentId` a top-level category is stored with, standing in for "no parent".
 *
 * A nullable column would be the obvious modelling choice and it is the reason the sibling
 * uniqueness index does not work: SQLite treats every NULL in a UNIQUE index as distinct from every
 * other, so with `parentId IS NULL` on every top-level row the index constrains nothing there.
 * "Food" and "food" both insert, and only sub-categories — the minority case — are actually
 * protected. Storing a value the index can compare is what makes the constraint real.
 *
 * Zero is safe as that value: `id` is `AUTOINCREMENT` and SQLite starts generated row ids at 1, and
 * Room asks for a generated one whenever an id is left at 0 — so no category can ever occupy this
 * row id and be mistaken for a parent. The domain keeps its nullable `Category.parentId`; the
 * sentinel is a storage detail and the mappers translate at the boundary.
 */
const val TOP_LEVEL_PARENT_ID: Long = 0

/**
 * A category row. [systemKey] is null for everything the user creates and holds the reserved key of
 * an app-owned fallback bucket otherwise; its unique index is what guarantees a kind can only ever
 * have one bucket, no matter how the row got there (created on demand, or restored from a backup).
 *
 * [name] is declared `COLLATE NOCASE` so the sibling-uniqueness index compares it the same way the
 * create/rename guards do — without a case-insensitive collation the index would happily accept
 * "Food" next to "food" and the guard's promise would only hold in one of the two places.
 *
 * There is deliberately no foreign key on [parentId]. It would have to resolve the sentinel, and no
 * row has id 0, so every top-level category would be rejected on insert; giving the constraint its
 * own nullable column instead would store one link twice and leave the two free to disagree.
 *
 * That leaves the parent link unenforced by the database, so it has to be enforced on the way in.
 * The use-cases cap the tree at two levels, which covers everything the app itself writes, and the
 * only other writer is a restore — so `decodeValidated` refuses a backup whose category points at a
 * missing parent, at itself, at a parent of another kind, or at one that is already a child. Weaken
 * either of those and a broken link survives into the table, where deleting such a category re-files
 * its transactions onto an id that does not exist, silently, because that column has no constraint
 * either.
 */
@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["systemKey"], unique = true),
        Index(value = ["kind", "parentId", "name"], unique = true),
    ],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val kind: String, // INCOME | EXPENSE
    val parentId: Long = TOP_LEVEL_PARENT_ID,
    val systemKey: String? = null,
)
