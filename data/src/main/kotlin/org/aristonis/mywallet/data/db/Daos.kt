package org.aristonis.mywallet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.aristonis.mywallet.domain.error.WalletException

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY date DESC, id DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun findById(id: Long): TransactionEntity?

    @Insert
    suspend fun insert(transaction: TransactionEntity): Long

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM transactions")
    suspend fun getAll(): List<TransactionEntity>

    @Insert
    suspend fun insertAll(rows: List<TransactionEntity>)

    @Query("DELETE FROM transactions")
    suspend fun clear()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun findById(id: Long): CategoryEntity?

    /**
     * The children of [parentId] that are of [kind]. The kind is part of the question, not a
     * refinement of it: a restore writes categories back without checking either the kind or the
     * parent of a row, so the table can hold an income child under an expense parent. Matching on
     * the parent alone would sweep that child into an expense reassignment and file its income
     * behind an expense label, where neither report would ever show it again.
     */
    @Query("SELECT * FROM categories WHERE parentId = :parentId AND kind = :kind ORDER BY name")
    suspend fun childrenOf(parentId: Long, kind: String): List<CategoryEntity>

    /** Children of [parentId] of any kind — paired with [childrenOf] to spot a cross-kind child. */
    @Query("SELECT COUNT(*) FROM categories WHERE parentId = :parentId")
    suspend fun childCountOf(parentId: Long): Int

    /** The app-owned bucket carrying [systemKey], or null if it has not been needed yet. */
    @Query("SELECT * FROM categories WHERE systemKey = :systemKey")
    suspend fun findBySystemKey(systemKey: String): CategoryEntity?

    /**
     * How many TOP-LEVEL categories of [kind] exist. The question this answers is "would the
     * add-transaction picker be empty?", and that picker only ever offers top-level rows — so a
     * kind left holding nothing but an orphaned sub-category has to count as empty. Counting every
     * row instead makes it look occupied, no fallback bucket is created, and the form's Save button
     * is disabled for good: the exact state the fallback exists to prevent.
     */
    @Query("SELECT COUNT(*) FROM categories WHERE kind = :kind AND parentId = $TOP_LEVEL_PARENT_ID")
    suspend fun countTopLevelOfKind(kind: String): Int

    @Upsert
    suspend fun upsert(category: CategoryEntity): Long

    @Query("DELETE FROM categories WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM categories")
    suspend fun getAll(): List<CategoryEntity>

    @Insert
    suspend fun insertAll(rows: List<CategoryEntity>)

    @Query("DELETE FROM categories")
    suspend fun clear()

    // --- Reassignment: the transaction-side half of deleting a category ---

    /**
     * Clears sub-category [subCategoryId] off every transaction that referenced it, and moves the
     * ones that named it as their MAIN category up to [parentId].
     *
     * For a row filed the ordinary way — parent in `categoryId`, child in `subCategoryId` — only
     * the finer label goes; `categoryId` is untouched on purpose, because the money was spent on
     * the parent category and still was, and rewriting it would silently restate every past report.
     * A row that put the child in `categoryId` instead is malformed, but it is the same spend under
     * the same parent, so it lands on that parent rather than being left naming a row this
     * statement is about to delete.
     *
     * Both halves are one statement so the changed-row count stays exact: a row that matched on
     * both columns is still one row, and the number the confirmation dialog quotes back to the user
     * is the number of transactions that actually moved.
     */
    @Query(
        "UPDATE transactions SET " +
            "categoryId = CASE WHEN categoryId = :subCategoryId THEN :parentId ELSE categoryId END, " +
            "subCategoryId = NULL " +
            "WHERE categoryId = :subCategoryId OR subCategoryId = :subCategoryId",
    )
    suspend fun detachSubCategory(subCategoryId: Long, parentId: Long): Int

    @Query(
        "SELECT COUNT(*) FROM transactions " +
            "WHERE categoryId IN (:categoryIds) OR subCategoryId IN (:categoryIds)",
    )
    suspend fun countReferencing(categoryIds: List<Long>): Int

    /**
     * Moves every transaction that referenced a doomed parent OR one of its children onto
     * [fallbackId]. Both columns move in one statement: clearing `subCategoryId` separately would
     * leave a window where a row points at a child that is about to disappear.
     */
    @Query(
        "UPDATE transactions SET categoryId = :fallbackId, subCategoryId = NULL " +
            "WHERE categoryId IN (:categoryIds) OR subCategoryId IN (:categoryIds)",
    )
    suspend fun reassignToFallback(categoryIds: List<Long>, fallbackId: Long): Int

    /**
     * Deletes [categoryId] and re-files the transactions that referenced it, returning how many of
     * them changed.
     *
     * `@Transaction` on a suspending method makes Room wrap the whole body in one database
     * transaction, so the child lookup, the bucket's get-or-create, the reassignment and the
     * deletes are a single atomic unit. That boundary has to cover the reads too: run the
     * get-or-create outside it and a rolled-back reassignment strands an empty bucket, and the list
     * of child ids would have been read from a snapshot the writes never saw.
     *
     * [fallbackName] is what the bucket is stored under, never what anyone sees: the screen resolves
     * a label from [fallbackKey]. The caller passes the key itself, because the stored name still
     * competes in the sibling-uniqueness index — a readable placeholder is a name a user might take
     * first, and then the bucket could never be created and every later delete of that kind would
     * fail with an error naming a category they believe is unrelated.
     */
    @Transaction
    suspend fun deleteAndReassign(
        categoryId: Long,
        kind: String,
        fallbackKey: String,
        fallbackName: String,
    ): Int {
        // Re-read inside the transaction: the caller's guards ran against an earlier snapshot, and
        // acting on one is how a delete goes wrong quietly. A row that vanished since must fail
        // loud rather than delete nothing and report zero — and the same argument applies to the
        // protection guard, so it is re-checked here too. Deleting a bucket that only became
        // app-owned after the caller looked (a restore landing mid-flight) would leave the next
        // delete of that kind with nowhere to move its transactions.
        val target = findById(categoryId) ?: throw WalletException.CategoryNotFound(categoryId)
        if (target.systemKey != null) throw WalletException.SystemCategoryProtected(categoryId)
        // Everything below reads the kind off the row rather than the parameter. Re-reading to
        // defeat a stale snapshot and then trusting the caller's idea of the kind would leave the
        // re-read half done, and a mismatch files a whole subtree under the wrong kind's bucket.
        if (target.kind != kind) throw WalletException.CategoryKindMismatch(categoryId)
        val targetKind = target.kind

        val isSubCategory = target.parentId != TOP_LEVEL_PARENT_ID
        val doomedIds =
            if (isSubCategory) listOf(target.id) else listOf(target.id) + childIdsOf(target.id, targetKind)

        val moved = if (isSubCategory) {
            detachSubCategory(subCategoryId = target.id, parentId = target.parentId)
        } else if (countReferencing(doomedIds) == 0) {
            // Nothing to move, so no reason to bring a bucket into existence yet.
            0
        } else {
            val fallbackId = resolveOrCreateBucket(targetKind, fallbackKey, fallbackName)
            // A restored backup can carry a bucket parented under the very category being deleted,
            // which puts the bucket in doomedIds: every reassigned row would be pointed at a
            // category this same call is about to remove, orphaning the lot in one go.
            if (fallbackId in doomedIds) throw WalletException.CategoryStructureInvalid(fallbackId)
            reassignToFallback(doomedIds, fallbackId)
        }

        deleteByIds(doomedIds)

        // A kind with no top-level category left would leave the add-transaction form with nothing
        // to pick and its Save button permanently disabled, so the bucket stands in as the last one.
        if (countTopLevelOfKind(targetKind) == 0) resolveOrCreateBucket(targetKind, fallbackKey, fallbackName)

        return moved
    }

    /**
     * The ids that go down with parent [parentId] of [kind]. A child of the OTHER kind stops the
     * delete instead of joining the group: it can only have come from a restore, which validates
     * neither kind nor parent, and there is no safe thing to do with it here. Taking it along files
     * its transactions under the wrong kind's bucket, where no report shows them; leaving it behind
     * strands it under a parent that no longer exists. Refusing at least says something is wrong
     * while the data is still intact.
     */
    suspend fun childIdsOf(parentId: Long, kind: String): List<Long> {
        val sameKind = childrenOf(parentId, kind)
        if (sameKind.size != childCountOf(parentId)) throw WalletException.CategoryKindMismatch(parentId)
        return sameKind.map { it.id }
    }

    /**
     * The bucket for [systemKey], created on first need. Idempotent within the enclosing
     * transaction: a second call finds what the first inserted.
     *
     * The insert's result is checked rather than returned as-is. A brand-new row that comes back
     * [UPSERT_NO_ROW_ID] was never written — here that means a top-level category of this kind
     * already holds [name], so the sibling-uniqueness index refused it. Handing that value on would
     * run `UPDATE transactions SET categoryId = -1` across every row being reassigned, without a
     * word, and there would be no row left to explain where the money went.
     */
    suspend fun resolveOrCreateBucket(kind: String, systemKey: String, name: String): Long {
        // The unique index guarantees at most one row per key. It does not guarantee that row is of
        // the right kind, or top-level — a restore writes categories verbatim. Reassigning onto a
        // sub-category is the very thing a transaction's main category may never be, and onto the
        // wrong kind files expenses under an income label where no report will ever show them.
        findBySystemKey(systemKey)?.let { existing ->
            if (existing.kind != kind || existing.parentId != TOP_LEVEL_PARENT_ID) {
                throw WalletException.CategoryStructureInvalid(existing.id)
            }
            return existing.id
        }
        val inserted = upsert(
            CategoryEntity(name = name, kind = kind, parentId = TOP_LEVEL_PARENT_ID, systemKey = systemKey),
        )
        if (inserted == UPSERT_NO_ROW_ID) throw WalletException.DuplicateCategoryName(name)
        return inserted
    }
}

@Dao
interface CurrencyDao {
    @Query("SELECT * FROM currencies ORDER BY code")
    fun observeAll(): Flow<List<CurrencyEntity>>

    @Query("SELECT * FROM currencies WHERE code = :code")
    suspend fun findByCode(code: String): CurrencyEntity?

    @Query("SELECT * FROM currencies")
    suspend fun getAll(): List<CurrencyEntity>

    @Insert
    suspend fun insertAll(rows: List<CurrencyEntity>)

    @Query("DELETE FROM currencies")
    suspend fun clear()
}

@Dao
interface RateDao {
    @Query("SELECT * FROM rates ORDER BY currencyCode")
    fun observeAll(): Flow<List<RateEntity>>

    @Query("SELECT * FROM rates WHERE currencyCode = :code")
    suspend fun findByCode(code: String): RateEntity?

    @Upsert
    suspend fun upsert(rate: RateEntity)

    @Query("SELECT * FROM rates")
    suspend fun getAll(): List<RateEntity>

    @Insert
    suspend fun insertAll(rows: List<RateEntity>)

    @Query("DELETE FROM rates")
    suspend fun clear()
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = $SETTINGS_ROW_ID")
    fun observe(): Flow<SettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = $SETTINGS_ROW_ID")
    suspend fun get(): SettingsEntity?

    @Upsert
    suspend fun upsert(settings: SettingsEntity)

    @Query("DELETE FROM settings")
    suspend fun clear()
}
