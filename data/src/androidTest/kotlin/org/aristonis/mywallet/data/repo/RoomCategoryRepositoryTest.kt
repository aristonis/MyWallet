package org.aristonis.mywallet.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.data.db.CategoryEntity
import org.aristonis.mywallet.data.db.TOP_LEVEL_PARENT_ID
import org.aristonis.mywallet.data.db.TransactionEntity
import org.aristonis.mywallet.data.db.WalletDatabase
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

    /** Every combination that the reassignment has to tell apart. */
    private val underGroceries = expense(id = 1, categoryId = 10, subCategoryId = 11)
    private val underRestaurants = expense(id = 2, categoryId = 10, subCategoryId = 12)
    private val underFoodOnly = expense(id = 3, categoryId = 10, subCategoryId = null)
    private val underTransport = expense(id = 4, categoryId = 20, subCategoryId = null)
    private val transfer = TransactionEntity(
        id = 5, type = "TRANSFER", date = LocalDate.of(2026, 2, 1),
        primaryAccountId = 1, primaryAmount = "20.00", primaryCurrency = "USD",
        secondaryAccountId = 2, secondaryAmount = "18.40", secondaryCurrency = "EUR", rateUsed = "0.92",
    )

    private fun expense(id: Long, categoryId: Long, subCategoryId: Long?) = TransactionEntity(
        id = id, type = "EXPENSE", date = LocalDate.of(2026, 2, 1),
        primaryAccountId = 1, primaryAmount = "12.99", primaryCurrency = "USD",
        categoryId = categoryId, subCategoryId = subCategoryId,
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
        db.transactionDao().insertAll(
            listOf(underGroceries, underRestaurants, underFoodOnly, underTransport, transfer),
        )
    }

    private suspend fun transaction(id: Long): TransactionEntity =
        requireNotNull(db.transactionDao().findById(id)) { "transaction $id is missing" }

    private suspend fun bucket(systemKey: String): CategoryEntity? = db.categoryDao().findBySystemKey(systemKey)

    private suspend fun categoryIds(): Set<Long> = db.categoryDao().getAll().map { it.id }.toSet()

    @Test
    fun deletingASubCategory_clearsTheSubCategoryAndLeavesTheParentCategoryOnTheRow() = runTest {
        seed()

        val moved = repository.deleteAndReassign(groceries.id, CategoryKind.EXPENSE, expenseBucketKey)

        assertEquals(1, moved)
        val row = transaction(underGroceries.id)
        // The single most important assertion in the whole feature: the spend was on Food and still
        // is. Moving categoryId here would silently rewrite every report that ever covered this row.
        assertEquals("categoryId must not change", food.id, row.categoryId)
        assertNull("the deleted sub-category must be cleared", row.subCategoryId)

        // Nothing else may be touched: not the sibling sub-category, not the parent, not the bucket.
        assertEquals(restaurants.id, transaction(underRestaurants.id).subCategoryId)
        assertEquals(food.id, transaction(underFoodOnly.id).categoryId)
        assertTrue("the parent must survive", food.id in categoryIds())
        assertNull("a sub-category delete never needs a bucket", bucket(expenseBucketKey))
    }

    @Test
    fun deletingAParent_movesRowsUnderItOrItsChildrenToTheFallbackBucket() = runTest {
        seed()

        val moved = repository.deleteAndReassign(food.id, CategoryKind.EXPENSE, expenseBucketKey)

        assertEquals("all three rows referencing Food or its children", 3, moved)
        val fallback = requireNotNull(bucket(expenseBucketKey)) { "the fallback bucket must exist" }
        assertEquals("EXPENSE", fallback.kind)
        // Storage spells "no parent" as the sentinel, not NULL — that is what lets the sibling
        // uniqueness index constrain top-level rows at all.
        assertEquals("the bucket is top-level", TOP_LEVEL_PARENT_ID, fallback.parentId)

        listOf(underGroceries, underRestaurants, underFoodOnly).forEach { seeded ->
            val row = transaction(seeded.id)
            assertEquals("row ${seeded.id} must move to the bucket", fallback.id, row.categoryId)
            assertNull("row ${seeded.id} must lose its sub-category", row.subCategoryId)
        }

        val remaining = categoryIds()
        assertTrue("the parent and both children are gone", remaining.intersect(setOf(10L, 11L, 12L)).isEmpty())
        assertEquals("an unrelated category is untouched", transport.id, transaction(underTransport.id).categoryId)

        // The point of nulling subCategoryId in the same statement: nothing may be left pointing at
        // a row that no longer exists, which the editor would refuse to save.
        db.transactionDao().getAll().forEach { row ->
            val category = row.categoryId
            val subCategory = row.subCategoryId
            assertTrue("row ${row.id} points at a deleted category", category == null || category in remaining)
            assertTrue("row ${row.id} points at a deleted sub-category", subCategory == null || subCategory in remaining)
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
        db.transactionDao().insertAll(listOf(underFoodOnly))

        try {
            repository.deleteAndReassign(food.id, CategoryKind.EXPENSE, expenseBucketKey)
            org.junit.Assert.fail("expected the malformed bucket to be refused")
        } catch (_: org.aristonis.mywallet.domain.error.WalletException.CategoryStructureInvalid) {
            // expected
        }

        assertTrue("nothing may be deleted", food.id in categoryIds())
        assertEquals("nothing may be reassigned", food.id, transaction(underFoodOnly.id).categoryId)
    }

    @Test
    fun theReturnedCountMatchesTheRowsThatActuallyChanged() = runTest {
        seed()
        val before = db.transactionDao().getAll().associateBy { it.id }

        val moved = repository.deleteAndReassign(food.id, CategoryKind.EXPENSE, expenseBucketKey)

        val actuallyChanged = db.transactionDao().getAll().count { it != before.getValue(it.id) }
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

        assertEquals("no income transaction existed to move", 0, moved)
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
        val before = db.transactionDao().getAll().toSet()

        var thrown: Throwable? = null
        try {
            repository.deleteAndReassign(food.id, CategoryKind.EXPENSE, expenseBucketKey)
        } catch (e: Throwable) {
            thrown = e
        }

        assertNotNull("a refused delete must surface, not be swallowed", thrown)
        assertNull("a rolled-back delete must not strand an orphan bucket", bucket(expenseBucketKey))
        assertEquals("no row may be left partially reassigned", before, db.transactionDao().getAll().toSet())
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
}
