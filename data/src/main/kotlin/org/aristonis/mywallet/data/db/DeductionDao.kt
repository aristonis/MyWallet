package org.aristonis.mywallet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * The deduction items, their auto-add links and the lines incomes snapshot from them. So far only
 * backup reads and writes these: a one-shot snapshot and a bulk replace, each list in key order.
 */
@Dao
interface DeductionDao {
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
