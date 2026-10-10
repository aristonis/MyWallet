package org.aristonis.mywallet.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.data.db.CategoryEntity
import org.aristonis.mywallet.data.db.CategoryRoleEntity
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.CategoryRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the real adapters against an in-memory database: the role row lives and dies with its category. */
@RunWith(AndroidJUnit4::class)
class RoomCategoryRoleRepositoryTest {

    private lateinit var db: WalletDatabase
    private lateinit var roles: RoomCategoryRoleRepository
    private lateinit var categories: RoomCategoryRepository

    private val interestIn = CategoryEntity(id = 1, name = "Interest", kind = "INCOME")
    private val interestOut = CategoryEntity(id = 2, name = "Interest", kind = "EXPENSE")
    private val fees = CategoryEntity(id = 3, name = "Fees", kind = "EXPENSE")

    private val expenseKey = Category.uncategorizedKeyFor(CategoryKind.EXPENSE)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WalletDatabase::class.java,
        ).allowMainThreadQueries().build()
        roles = RoomCategoryRoleRepository(db.categoryRoleDao())
        categories = RoomCategoryRepository(db.categoryDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedRoles() {
        db.categoryDao().insertAll(listOf(interestIn, interestOut, fees))
        db.categoryRoleDao().insertAll(
            listOf(
                CategoryRoleEntity(CategoryRole.INTEREST_IN.name, interestIn.id),
                CategoryRoleEntity(CategoryRole.INTEREST_OUT.name, interestOut.id),
                CategoryRoleEntity(CategoryRole.FEES.name, fees.id),
            ),
        )
    }

    @Test
    fun seededRolesResolveToTheirCategories() = runTest {
        seedRoles()

        assertEquals(interestIn.id, roles.categoryFor(CategoryRole.INTEREST_IN))
        assertEquals(interestOut.id, roles.categoryFor(CategoryRole.INTEREST_OUT))
        assertEquals(fees.id, roles.categoryFor(CategoryRole.FEES))
    }

    @Test
    fun aRoleWithNoRowResolvesToNull() = runTest {
        seedRoles()

        assertNull(roles.categoryFor(CategoryRole.LOST_DEBT))
    }

    @Test
    fun deletingTheRolesCategoryDropsTheRoleRow() = runTest {
        seedRoles()

        categories.deleteAndReassign(fees.id, CategoryKind.EXPENSE, expenseKey)

        assertNull(roles.categoryFor(CategoryRole.FEES))
        assertEquals("other roles are untouched", interestOut.id, roles.categoryFor(CategoryRole.INTEREST_OUT))
    }

    @Test
    fun bucketForCreatesTheBucketOnceAndReturnsTheSameId() = runTest {
        val first = categories.bucketFor(CategoryKind.EXPENSE)
        val second = categories.bucketFor(CategoryKind.EXPENSE)

        assertEquals(first, second)
        val row = db.categoryDao().findBySystemKey(expenseKey)
        assertNotNull(row)
        assertEquals(first, row!!.id)
        assertEquals("EXPENSE", row.kind)
        assertEquals(1, db.categoryDao().getAll().count { it.systemKey == expenseKey })
    }

    @Test
    fun aRoleBoundToACategoryOfTheWrongKindResolvesToNull() = runTest {
        db.categoryDao().insertAll(listOf(interestIn, fees))
        // A restore writes bindings verbatim, so an income category can sit under an expense role.
        db.categoryRoleDao().insertAll(listOf(CategoryRoleEntity(CategoryRole.FEES.name, interestIn.id)))

        assertNull(roles.categoryFor(CategoryRole.FEES))
    }

    @Test
    fun aRoleBoundToASubCategoryResolvesToNull() = runTest {
        val parent = CategoryEntity(id = 8, name = "Bank", kind = "EXPENSE")
        val child = CategoryEntity(id = 9, name = "Wire fees", kind = "EXPENSE", parentId = 8)
        db.categoryDao().insertAll(listOf(parent, child))
        db.categoryRoleDao().insertAll(listOf(CategoryRoleEntity(CategoryRole.FEES.name, child.id)))

        assertNull(roles.categoryFor(CategoryRole.FEES))
    }

    @Test
    fun bucketForCreatesTheIncomeBucketOnce() = runTest {
        val incomeKey = Category.uncategorizedKeyFor(CategoryKind.INCOME)

        val first = categories.bucketFor(CategoryKind.INCOME)
        val second = categories.bucketFor(CategoryKind.INCOME)

        assertEquals(first, second)
        val row = db.categoryDao().findBySystemKey(incomeKey)
        assertNotNull(row)
        assertEquals("INCOME", row!!.kind)
        assertEquals(1, db.categoryDao().getAll().count { it.systemKey == incomeKey })
    }

    @Test
    fun bucketForRefusesARowOfTheWrongKind() = runTest {
        val wrongKind = CategoryEntity(id = 9, name = expenseKey, kind = "INCOME", systemKey = expenseKey)
        db.categoryDao().insertAll(listOf(wrongKind))

        try {
            categories.bucketFor(CategoryKind.EXPENSE)
            fail("expected CategoryStructureInvalid")
        } catch (e: WalletException.CategoryStructureInvalid) {
            // the bucket must not be used for the wrong kind
        }
    }

    @Test
    fun bucketForRefusesARowThatIsNotTopLevel() = runTest {
        val parent = CategoryEntity(id = 8, name = "Food", kind = "EXPENSE")
        val nested = CategoryEntity(id = 9, name = expenseKey, kind = "EXPENSE", parentId = 8, systemKey = expenseKey)
        db.categoryDao().insertAll(listOf(parent, nested))

        try {
            categories.bucketFor(CategoryKind.EXPENSE)
            fail("expected CategoryStructureInvalid")
        } catch (e: WalletException.CategoryStructureInvalid) {
            // a sub-category may never be a leg's main category
        }
    }
}
