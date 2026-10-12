package org.aristonis.mywallet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** The persisted deduction rules, their default category links and the lines income entries snapshot. */
@Dao
interface DeductionDao {
    @Transaction
    @Query("SELECT * FROM deduction_items ORDER BY sortOrder, id")
    fun observeAllItemsWithCategories(): Flow<List<DeductionItemWithAutoCategories>>

    @Query("SELECT * FROM deduction_items WHERE id = :id")
    suspend fun findItem(id: Long): DeductionItemEntity?

    @Query("SELECT * FROM deduction_items WHERE nameKey = :nameKey LIMIT 1")
    suspend fun findItemByNameKey(nameKey: String): DeductionItemEntity?

    @Insert
    suspend fun insertItem(row: DeductionItemEntity): Long

    @Update
    suspend fun updateItem(row: DeductionItemEntity): Int

    @Query(
        "SELECT incomeCategoryId FROM deduction_item_auto_categories " +
            "WHERE itemId = :itemId ORDER BY incomeCategoryId",
    )
    suspend fun autoCategoryIds(itemId: Long): List<Long>

    @Query("DELETE FROM deduction_item_auto_categories WHERE itemId = :itemId")
    suspend fun clearAutoCategories(itemId: Long)

    @Insert
    suspend fun insertAutoCategoriesForItem(rows: List<DeductionItemAutoCategoryEntity>)

    @Query("SELECT EXISTS(SELECT 1 FROM income_deduction_lines WHERE itemId = :itemId)")
    suspend fun isItemInUse(itemId: Long): Boolean

    @Query("DELETE FROM deduction_items WHERE id = :id")
    suspend fun deleteItem(id: Long): Int

    @Query("SELECT * FROM deduction_items ORDER BY id")
    suspend fun getAllItems(): List<DeductionItemEntity>

    @Query("SELECT * FROM deduction_item_auto_categories ORDER BY itemId, incomeCategoryId")
    suspend fun getAllAutoCategories(): List<DeductionItemAutoCategoryEntity>

    @Query("SELECT * FROM income_deduction_lines ORDER BY id")
    suspend fun getAllLines(): List<IncomeDeductionLineEntity>

    @Insert
    suspend fun insertItems(rows: List<DeductionItemEntity>)

    @Insert
    suspend fun insertAutoCategories(rows: List<DeductionItemAutoCategoryEntity>)

    @Insert
    suspend fun insertLines(rows: List<IncomeDeductionLineEntity>)

    /**
     * Fails while any deduction line still uses an item, so clear the entries (and their lines)
     * first. The auto-add links go with their item.
     */
    @Query("DELETE FROM deduction_items")
    suspend fun clearItems()
}
