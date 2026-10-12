package org.aristonis.mywallet.data.repo

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.data.db.CategoryEntity
import org.aristonis.mywallet.data.db.DeductionItemAutoCategoryEntity
import org.aristonis.mywallet.data.db.EntryEntity
import org.aristonis.mywallet.data.db.IncomeDeductionLineEntity
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.data.db.scalar
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DeductionItem
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate

/** Exercises the Room adapter and its key/FK constraints against a real in-memory schema. */
@RunWith(AndroidJUnit4::class)
class RoomDeductionItemRepositoryTest {

    private lateinit var db: WalletDatabase
    private lateinit var repository: RoomDeductionItemRepository

    private val incomeCategory = CategoryEntity(id = 10, name = "Salary", kind = "INCOME")
    private val secondIncomeCategory = CategoryEntity(id = 11, name = "Freelance", kind = "INCOME")
    private val expenseCategory = CategoryEntity(id = 20, name = "Tax", kind = "EXPENSE")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WalletDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomDeductionItemRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedCategories() {
        db.categoryDao().insertAll(listOf(incomeCategory, secondIncomeCategory, expenseCategory))
    }

    private fun linkCount(itemId: Long) = db.openHelper.writableDatabase
        .scalar("SELECT COUNT(*) FROM deduction_item_auto_categories WHERE itemId = ?", itemId)

    private fun item(
        name: String = "Tax",
        autoAddCategoryIds: Set<Long> = setOf(incomeCategory.id, secondIncomeCategory.id),
    ) = DeductionItem(
        name = name,
        mode = DeductionMode.FIXED,
        value = BigDecimal("12.3400"),
        valueCurrencyCode = "USD",
        timing = DeductionTiming.LATER,
        expenseCategoryId = expenseCategory.id,
        autoAddCategoryIds = autoAddCategoryIds,
    )

    @Test
    fun upsertPersistsTheNameKeyAndMapsItemFieldsAndAutoCategories() = runTest {
        seedCategories()

        val id = repository.upsert(item(name = "  TÁX  "))
        val stored = repository.findById(id)

        assertEquals(
            "táx",
            db.openHelper.writableDatabase.scalar("SELECT nameKey FROM deduction_items WHERE id = ?", id),
        )
        assertNotNull(stored)
        assertEquals("TÁX", stored!!.name)
        assertEquals(BigDecimal("12.3400"), stored.value)
        assertEquals("USD", stored.valueCurrencyCode)
        assertEquals(expenseCategory.id, stored.expenseCategoryId)
        assertEquals(setOf(incomeCategory.id, secondIncomeCategory.id), stored.autoAddCategoryIds)
    }

    @Test
    fun aNameThatFoldsToAnExistingKeyIsRefusedWithoutReplacingTheExistingItem() = runTest {
        seedCategories()
        val id = repository.upsert(item(name = "Tax"))

        val duplicate = runCatching { repository.upsert(item(name = "  TAX  ")) }

        assertTrue("the unique name key must reject the duplicate", duplicate.isFailure)
        assertTrue(
            "the duplicate must report the typed name conflict",
            duplicate.exceptionOrNull() is WalletException.DeductionItemNameTaken,
        )
        assertEquals("Tax", repository.findById(id)?.name)
        assertEquals(
            "1",
            db.openHelper.writableDatabase.scalar("SELECT COUNT(*) FROM deduction_items WHERE nameKey = 'tax'"),
        )
    }

    @Test
    fun deletingAnItemUsedByAnIncomeFailsWithTypedRefusalAndForeignKeyRestrict() = runTest {
        seedCategories()
        val id = repository.upsert(item(autoAddCategoryIds = emptySet()))
        db.entryDao().insertEntry(EntryEntity(id = 50, kind = "INCOME", date = LocalDate.of(2026, 10, 1)))
        db.deductionDao().insertLines(
            listOf(
                IncomeDeductionLineEntity(
                    entryId = 50,
                    itemId = id,
                    position = 0,
                    name = "Tax",
                    mode = "FIXED",
                    value = "12.3400",
                    valueCurrencyCode = "USD",
                    timing = "LATER",
                ),
            ),
        )

        try {
            repository.delete(id)
            throw AssertionError("expected a typed in-use refusal")
        } catch (failure: WalletException.DeductionItemInUse) {
            assertEquals(id, failure.id)
        }
        assertNotNull("the item must remain after refusal", repository.findById(id))

        val sqlFailure = runCatching {
            db.openHelper.writableDatabase.execSQL("DELETE FROM deduction_items WHERE id = ?", arrayOf(id))
        }
        assertTrue(
            "the database FK must independently restrict deletion",
            sqlFailure.exceptionOrNull() is SQLiteConstraintException,
        )
        assertEquals(
            "1",
            db.openHelper.writableDatabase.scalar("SELECT COUNT(*) FROM income_deduction_lines WHERE itemId = ?", id),
        )
    }

    @Test
    fun deletingAnUnusedItemCascadesItsAutoCategoryLinks() = runTest {
        seedCategories()
        val id = repository.upsert(item())
        assertEquals("2", linkCount(id))

        repository.delete(id)

        assertNull(repository.findById(id))
        assertEquals("0", linkCount(id))
    }

    @Test
    fun deletingAnIncomeCategoryCascadesOnlyItsAutoCategoryLink() = runTest {
        seedCategories()
        val id = repository.upsert(item())

        db.categoryDao().deleteByIds(listOf(incomeCategory.id))

        val links = db.deductionDao().getAllAutoCategories()
        assertEquals(listOf(DeductionItemAutoCategoryEntity(id, secondIncomeCategory.id)), links)
        assertFalse(links.any { it.incomeCategoryId == incomeCategory.id })
        assertEquals(setOf(secondIncomeCategory.id), repository.findById(id)?.autoAddCategoryIds)
    }

    @Test
    fun savingAnItemWhoseIdWasDeletedElsewhereFailsWithNotFoundAndStoresNothing() = runTest {
        seedCategories()
        val id = repository.upsert(item())
        repository.delete(id)

        val failure = runCatching { repository.upsert(item().copy(id = id, name = "Renamed")) }

        val thrown = failure.exceptionOrNull()
        assertTrue("was $thrown", thrown is WalletException.DeductionItemNotFound)
        assertEquals(id, (thrown as WalletException.DeductionItemNotFound).id)
        assertNull(repository.findById(id))
        assertEquals("0", db.openHelper.writableDatabase.scalar("SELECT COUNT(*) FROM deduction_items"))
        assertEquals("0", linkCount(id))
    }

    @Test
    fun editingAnItemsDefaultNameAndCategoryLeavesTheRecordedLinesUnchanged() = runTest {
        seedCategories()
        val id = repository.upsert(item())
        db.entryDao().insertEntry(EntryEntity(id = 50, kind = "INCOME", date = LocalDate.of(2026, 10, 1)))
        db.deductionDao().insertLines(
            listOf(
                IncomeDeductionLineEntity(
                    entryId = 50, itemId = id, position = 0, name = "Tax", mode = "FIXED", value = "12.3400",
                    valueCurrencyCode = "USD", timing = "LATER",
                ),
            ),
        )
        val linesBefore = db.deductionDao().getAllLines()

        repository.upsert(
            item(name = "Income tax", autoAddCategoryIds = setOf(incomeCategory.id)).copy(
                id = id,
                mode = DeductionMode.PERCENT,
                value = BigDecimal("7.5"),
                valueCurrencyCode = null,
                timing = DeductionTiming.NOW,
                expenseCategoryId = secondIncomeCategory.id,
            ),
        )

        assertEquals("Income tax", repository.findById(id)?.name)
        assertEquals(linesBefore, db.deductionDao().getAllLines())
    }
}
