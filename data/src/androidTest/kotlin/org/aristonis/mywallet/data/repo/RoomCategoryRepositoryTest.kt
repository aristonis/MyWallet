package org.aristonis.mywallet.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.data.db.CategoryEntity
import org.aristonis.mywallet.data.db.TOP_LEVEL_PARENT_ID
import org.aristonis.mywallet.data.db.EntryEntity
import org.aristonis.mywallet.data.db.EntryLegEntity
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.data.db.scalar
import org.aristonis.mywallet.data.db.textRows
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Drives the real Room adapter against an in-memory database, because deleting a category is a
 * multi-statement, two-table operation whose whole contract is what the columns look like
 * afterwards — something no fake can prove.
 *
 * Ids are fixed constants so each assertion can name the row it means.
 */
@RunWith(AndroidJUnit4::class)
class RoomCategoryRepositoryTest {

    private lateinit var db: WalletDatabase
    private lateinit var repository: RoomCategoryRepository

    private val food = CategoryEntity(id = 10, name = "Food", kind = "EXPENSE")
    private val groceries = CategoryEntity(id = 11, name = "Groceries", kind = "EXPENSE", parentId = 10)
    private val restaurants = CategoryEntity(id = 12, name = "Restaurants", kind = "EXPENSE", parentId = 10)
    private val transport = CategoryEntity(id = 20, name = "Transport", kind = "EXPENSE")
    private val salary = CategoryEntity(id = 30, name = "Salary", kind = "INCOME")
    private val health = CategoryEntity(id = 40, name = "Health", kind = "EXPENSE")

    /** Every combination that the reassignment has to tell apart, as entry ids. */
    private val underGroceries = 1L
    private val underRestaurants = 2L
    private val underFoodOnly = 3L
    private val underTransport = 4L
    private val transfer = 5L

    private val february = LocalDate.of(2026, 2, 1)

    /** An expense's two legs: money out of an account, and the same amount on its category. */
    private fun expenseLegs(entryId: Long, categoryId: Long, subCategoryId: Long?) = listOf(
        EntryLegEntity(entryId = entryId, role = "ACCOUNT", position = 0, accountId = 1, amount = "-12.99", currencyCode = "USD"),
        EntryLegEntity(
            entryId = entryId, role = "MAIN", position = 0, categoryId = categoryId, subCategoryId = subCategoryId,
            amount = "12.99", currencyCode = "USD", rateToBase = "1",
        ),
    )

    private val transferLegs = listOf(
        EntryLegEntity(entryId = transfer, role = "SOURCE", position = 0, accountId = 1, amount = "-20.00", currencyCode = "USD"),
        EntryLegEntity(entryId = transfer, role = "DEST", position = 0, accountId = 2, amount = "18.40", currencyCode = "EUR"),
    )

    private val expenseBucketKey = Category.uncategorizedKeyFor(CategoryKind.EXPENSE)
    private val incomeBucketKey = Category.uncategorizedKeyFor(CategoryKind.INCOME)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WalletDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomCategoryRepository(db.categoryDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed() {
        db.categoryDao().insertAll(listOf(food, groceries, restaurants, transport, salary, health))
        insertExpense(underGroceries, categoryId = 10, subCategoryId = 11)
        insertExpense(underRestaurants, categoryId = 10, subCategoryId = 12)
        insertExpense(underFoodOnly, categoryId = 10, subCategoryId = null)
        insertExpense(underTransport, categoryId = 20, subCategoryId = null)
        db.entryDao().insertAll(listOf(EntryEntity(id = transfer, kind = "TRANSFER", date = february, pairRate = "0.92")))
        db.entryDao().insertLegs(transferLegs)
    }

    private suspend fun insertExpense(entryId: Long, categoryId: Long, subCategoryId: Long?) {
        db.entryDao().insertAll(listOf(EntryEntity(id = entryId, kind = "EXPENSE", date = february)))
        db.entryDao().insertLegs(expenseLegs(entryId, categoryId, subCategoryId))
    }

    /** The category leg of entry [entryId]. */
    private suspend fun mainLeg(entryId: Long): EntryLegEntity =
        db.entryDao().getAllLegs().single { it.entryId == entryId && it.role == "MAIN" }

    private suspend fun bucket(systemKey: String): CategoryEntity? = db.categoryDao().findBySystemKey(systemKey)

    private suspend fun categoryIds(): Set<Long> = db.categoryDao().getAll().map { it.id }.toSet()

    @Test
    fun deletingASubCategory_clearsTheSubCategoryAndLeavesTheParentCategoryOnTheRow() = runTest {
        seed()

        val moved = repository.deleteAndReassign(groceries.id, CategoryKind.EXPENSE, expenseBucketKey)

        assertEquals(1, moved)
        val leg = mainLeg(underGroceries)
        // The single most important assertion in the whole feature: the spend was on Food and still
        // is. Moving categoryId here would silently rewrite every report that ever covered this leg.
        assertEquals("categoryId must not change", food.id, leg.categoryId)
        assertNull("the deleted sub-category must be cleared", leg.subCategoryId)

        // Nothing else may be touched: not the sibling sub-category, not the parent, not the bucket.
        assertEquals(restaurants.id, mainLeg(underRestaurants).subCategoryId)
        assertEquals(food.id, mainLeg(underFoodOnly).categoryId)
        assertTrue("the parent must survive", food.id in categoryIds())
        assertNull("a sub-category delete never needs a bucket", bucket(expenseBucketKey))
    }

    @Test
    fun deletingAParent_movesRowsUnderItOrItsChildrenToTheFallbackBucket() = runTest {
        seed()

        val moved = repository.deleteAndReassign(food.id, CategoryKind.EXPENSE, expenseBucketKey)

        assertEquals("all three entries referencing Food or its children", 3, moved)
        val fallback = requireNotNull(bucket(expenseBucketKey)) { "the fallback bucket must exist" }
        assertEquals("EXPENSE", fallback.kind)
        // Storage spells "no parent" as the sentinel, not NULL — that is what lets the sibling
        // uniqueness index constrain top-level rows at all.
        assertEquals("the bucket is top-level", TOP_LEVEL_PARENT_ID, fallback.parentId)

        listOf(underGroceries, underRestaurants, underFoodOnly).forEach { entryId ->
            val leg = mainLeg(entryId)
            assertEquals("entry $entryId must move to the bucket", fallback.id, leg.categoryId)
            assertNull("entry $entryId must lose its sub-category", leg.subCategoryId)
        }

        val remaining = categoryIds()
        assertTrue("the parent and both children are gone", remaining.intersect(setOf(10L, 11L, 12L)).isEmpty())
        assertEquals("an unrelated category is untouched", transport.id, mainLeg(underTransport).categoryId)

        // The point of nulling subCategoryId in the same statement: nothing may be left pointing at
        // a row that no longer exists, which the editor would refuse to save.
        db.entryDao().getAllLegs().forEach { leg ->
            val category = leg.categoryId
            val subCategory = leg.subCategoryId
            assertTrue("leg ${leg.id} points at a deleted category", category == null || category in remaining)
            assertTrue("leg ${leg.id} points at a deleted sub-category", subCategory == null || subCategory in remaining)
        }
    }

    @Test
    fun aBucketRowOfTheWrongShapeIsRefusedRatherThanReassignedOnto() = runTest {
        // A restore writes categories verbatim, so the row carrying a reserved key can arrive as the
        // wrong kind or as a sub-category. Reassigning onto it would file expenses under an income
        // label, or under a sub-category — which is exactly what a main category may never be.
        db.categoryDao().insertAll(
            listOf(
                food,
                CategoryEntity(
                    id = 77, name = expenseBucketKey, kind = "INCOME",
                    parentId = TOP_LEVEL_PARENT_ID, systemKey = expenseBucketKey,
                ),
            ),
        )
        insertExpense(underFoodOnly, categoryId = 10, subCategoryId = null)

        try {
            repository.deleteAndReassign(food.id, CategoryKind.EXPENSE, expenseBucketKey)
            org.junit.Assert.fail("expected the malformed bucket to be refused")
        } catch (_: org.aristonis.mywallet.domain.error.WalletException.CategoryStructureInvalid) {
            // expected
        }

        assertTrue("nothing may be deleted", food.id in categoryIds())
        assertEquals("nothing may be reassigned", food.id, mainLeg(underFoodOnly).categoryId)
    }

    @Test
    fun theReturnedCountMatchesTheRowsThatActuallyChanged() = runTest {
        seed()
        val before = db.entryDao().getAllLegs().groupBy { it.entryId }

        val moved = repository.deleteAndReassign(food.id, CategoryKind.EXPENSE, expenseBucketKey)

        val actuallyChanged = db.entryDao().getAllLegs().groupBy { it.entryId }.count { (entryId, legs) -> legs != before.getValue(entryId) }
        assertEquals("the confirmation dialog quotes this number back to the user", actuallyChanged, moved)
    }

    @Test
    fun deletingACategoryNothingRefersTo_doesNotBringABucketIntoExistence() = runTest {
        seed()

        val moved = repository.deleteAndReassign(health.id, CategoryKind.EXPENSE, expenseBucketKey)

        assertEquals(0, moved)
        assertNull("an unused bucket would just be clutter in the picker", bucket(expenseBucketKey))
    }

    @Test
    fun deletingTheLastCategoryOfAKind_leavesTheBucketBehind() = runTest {
        seed()

        val moved = repository.deleteAndReassign(salary.id, CategoryKind.INCOME, incomeBucketKey)

        assertEquals("no income entry existed to move", 0, moved)
        // A kind with nothing in it leaves the add-transaction form with nothing to pick and its
        // Save button permanently disabled, so the bucket has to stand in even with nothing to hold.
        assertNotNull("INCOME must not be left empty", bucket(incomeBucketKey))
        assertNull("the other kind still has categories, so it gets no bucket", bucket(expenseBucketKey))
    }

    @Test
    fun aFailureMidOperation_rollsBackTheBucketAndEveryReassignment() = runTest {
        seed()
        // Refuse the delete at the database level. It fires after the bucket has been created and
        // the rows reassigned, which is exactly the half-finished state that must not survive.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER refuse_category_delete BEFORE DELETE ON categories " +
                "BEGIN SELECT RAISE(ABORT, 'delete refused'); END",
        )
        val before = db.entryDao().getAllLegs().toSet()

        var thrown: Throwable? = null
        try {
            repository.deleteAndReassign(food.id, CategoryKind.EXPENSE, expenseBucketKey)
        } catch (e: Throwable) {
            thrown = e
        }

        assertNotNull("a refused delete must surface, not be swallowed", thrown)
        assertNull("a rolled-back delete must not strand an orphan bucket", bucket(expenseBucketKey))
        assertEquals("no leg may be left partially reassigned", before, db.entryDao().getAllLegs().toSet())
        assertEquals("every category must still be there", setOf(10L, 11L, 12L, 20L, 30L, 40L), categoryIds())
    }

    @Test
    fun upsert_returnsTheIdOfANewCategoryAndKeepsTheIdOfAnEditedOne() = runTest {
        val created = repository.upsert(Category(name = "Books", kind = CategoryKind.EXPENSE))
        assertTrue("a new row gets a generated id", created > 0)

        val renamed = repository.upsert(
            Category(id = created, name = "Reading", kind = CategoryKind.EXPENSE),
        )

        assertEquals("an edit keeps its id", created, renamed)
        assertEquals("Reading", db.categoryDao().findById(created)?.name)
    }

    // --- Entries on legs: a category's money lives on entry legs and deduction items, not on rows of its own. ---

    private val sql get() = db.openHelper.writableDatabase

    /** An entry with an account leg and one MAIN category leg, written as plain rows. */
    private fun entryOn(entryId: Long, kind: String, categoryId: Long, subCategoryId: Long?) {
        sql.execSQL("INSERT INTO entries (id, kind, date) VALUES (?, ?, 20454)", arrayOf<Any?>(entryId, kind))
        sql.execSQL(
            "INSERT INTO entry_legs (entryId, role, position, accountId, amount, currencyCode) " +
                "VALUES (?, 'ACCOUNT', 0, 1, '12.99', 'USD')",
            arrayOf<Any?>(entryId),
        )
        sql.execSQL(
            "INSERT INTO entry_legs (entryId, role, position, categoryId, subCategoryId, amount, currencyCode, rateToBase) " +
                "VALUES (?, 'MAIN', 0, ?, ?, '12.99', 'USD', '1')",
            arrayOf<Any?>(entryId, categoryId, subCategoryId),
        )
    }

    private fun mainLegOf(entryId: Long): List<Long?> =
        sql.textRows("SELECT categoryId, subCategoryId FROM entry_legs WHERE entryId = ? AND role = 'MAIN'", entryId)
            .single().map { it?.toLong() }

    private fun deductionItem(id: Long, expenseCategoryId: Long) {
        sql.execSQL(
            "INSERT INTO deduction_items (id, name, nameKey, mode, value, valueCurrencyCode, timing, " +
                "expenseCategoryId, enabled, sortOrder) VALUES (?, ?, ?, 'PERCENT', '5', NULL, 'NOW', ?, 1, 0)",
            arrayOf<Any?>(id, "Item $id", "item $id", expenseCategoryId),
        )
    }

    private fun itemCategory(id: Long): Long =
        requireNotNull(sql.scalar("SELECT expenseCategoryId FROM deduction_items WHERE id = ?", id)).toLong()

    private suspend fun seedCategories() {
        db.categoryDao().insertAll(listOf(food, groceries, restaurants, transport, salary, health))
    }

    @Test
    fun deletingACategoryRefilesItsLegsOntoUncategorizedAndLeavesOtherLegsAlone() = runTest {
        seedCategories()
        entryOn(1, "EXPENSE", categoryId = 10, subCategoryId = 11)
        entryOn(2, "EXPENSE", categoryId = 10, subCategoryId = null)
        entryOn(3, "EXPENSE", categoryId = 20, subCategoryId = null)

        val moved = repository.deleteAndReassign(food.id, CategoryKind.EXPENSE, expenseBucketKey)

        val fallback = requireNotNull(bucket(expenseBucketKey)) { "the fallback bucket must exist" }
        assertEquals("two entries had a leg on Food", 2, moved)
        assertEquals(listOf<Long?>(fallback.id, null), mainLegOf(1))
        assertEquals(listOf<Long?>(fallback.id, null), mainLegOf(2))
        assertEquals("a leg on another category is untouched", listOf<Long?>(20, null), mainLegOf(3))
        assertEquals("no entry is lost with its category", "3", sql.scalar("SELECT COUNT(*) FROM entries"))
        assertEquals("the account legs are untouched", "3", sql.scalar("SELECT COUNT(*) FROM entry_legs WHERE role = 'ACCOUNT'"))
    }

    @Test
    fun deletingASubCategoryClearsItOffTheLegAndKeepsTheParentOnIt() = runTest {
        seedCategories()
        entryOn(1, "EXPENSE", categoryId = 10, subCategoryId = 11)
        entryOn(2, "EXPENSE", categoryId = 10, subCategoryId = 12)

        val moved = repository.deleteAndReassign(groceries.id, CategoryKind.EXPENSE, expenseBucketKey)

        assertEquals(1, moved)
        assertEquals("the spend was on Food and still is", listOf<Long?>(10, null), mainLegOf(1))
        assertEquals(listOf<Long?>(10, 12), mainLegOf(2))
        assertNull("a sub-category delete never needs a bucket", bucket(expenseBucketKey))
    }

    @Test
    fun deletingAnIncomeCategoryRefilesItsLegsOntoTheIncomeBucket() = runTest {
        seedCategories()
        entryOn(1, "INCOME", categoryId = 30, subCategoryId = null)

        repository.deleteAndReassign(salary.id, CategoryKind.INCOME, incomeBucketKey)

        val fallback = requireNotNull(bucket(incomeBucketKey)) { "the income bucket must exist" }
        assertEquals("INCOME", fallback.kind)
        assertEquals(listOf<Long?>(fallback.id, null), mainLegOf(1))
    }

    @Test
    fun deletingACategoryReassignsTheDeductionItemsThatFileTheirExpenseUnderIt() = runTest {
        seedCategories()
        deductionItem(1, expenseCategoryId = 10)
        deductionItem(2, expenseCategoryId = 11)
        deductionItem(3, expenseCategoryId = 20)

        repository.deleteAndReassign(food.id, CategoryKind.EXPENSE, expenseBucketKey)

        val fallback = requireNotNull(bucket(expenseBucketKey)) { "the fallback bucket must exist" }
        assertEquals("an item on the parent moves to the bucket", fallback.id, itemCategory(1))
        assertEquals("an item on a child of the parent moves too", fallback.id, itemCategory(2))
        assertEquals("an item on another category is untouched", transport.id, itemCategory(3))
    }

    @Test
    fun aCategoryOnlyADeductionItemUsesIsStillRefiled() = runTest {
        // No entry refers to it, but the item would be left naming a category that no longer exists.
        seedCategories()
        deductionItem(1, expenseCategoryId = 40)

        repository.deleteAndReassign(health.id, CategoryKind.EXPENSE, expenseBucketKey)

        val fallback = requireNotNull(bucket(expenseBucketKey)) { "the fallback bucket must exist" }
        assertEquals(fallback.id, itemCategory(1))
    }
}
