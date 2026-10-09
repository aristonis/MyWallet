package org.aristonis.mywallet.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Builds the database the way the app builds it, seed callback attached.
 *
 * Every other test here uses a bare in-memory builder, so the first-run SQL had no coverage at all
 * and its statements were free to drift from the schema — which they did: the seed still wrote a
 * top-level parent as NULL after the column became NOT NULL, so the very first insert aborted
 * `onCreate` and no fresh install could open its own database. Nothing in the build caught it,
 * because nothing ran it. This is the test that runs it.
 */
@RunWith(AndroidJUnit4::class)
class SeedCallbackTest {

    private lateinit var db: WalletDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WalletDatabase::class.java,
        ).addCallback(DatabaseFactory.SeedCallback).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    /** The callback runs on first access, not on build. */
    private suspend fun categories() = db.categoryDao().getAll()

    @Test
    fun aFreshDatabaseSeedsWithoutThrowing() = runTest {
        assertTrue("the seed produced no categories", categories().isNotEmpty())
        assertTrue("the seed produced no currencies", db.currencyDao().getAll().isNotEmpty())
    }

    @Test
    fun everySeededTopLevelCategoryUsesTheSentinelNotNull() = runTest {
        val topLevel = categories().filter { it.parentId == TOP_LEVEL_PARENT_ID }
        // The starter categories and the pre-made ones are both seeded at top level.
        val seeded = DefaultData.categories.map { it.kind.name to it.name } +
            DefaultData.preMadeCategories.map { it.kind.name to it.name }

        assertEquals(seeded.size, topLevel.size)
        assertEquals(
            "every seeded name is present at top level",
            seeded.toSet(),
            topLevel.map { it.kind to it.name }.toSet(),
        )
    }

    @Test
    fun everySeededSubCategoryResolvesToARealParentOfTheSameKind() = runTest {
        val all = categories()
        val byId = all.associateBy { it.id }
        val children = all.filter { it.parentId != TOP_LEVEL_PARENT_ID }

        assertEquals(DefaultData.subCategories.size, children.size)
        children.forEach { child ->
            val parent = byId[child.parentId]
            requireNotNull(parent) { "sub-category ${child.name} points at a parent that does not exist" }
            assertEquals("${child.name} must share its parent's kind", parent.kind, child.kind)
            assertEquals("${child.name}'s parent must be top-level", TOP_LEVEL_PARENT_ID, parent.parentId)
        }
    }

    @Test
    fun theSeedLeavesNoCategoryOwnedByTheApp() = runTest {
        // The fallback bucket is created on demand, never seeded — seeding one would make it a row
        // a restore could then duplicate.
        assertTrue(categories().all { it.systemKey == null })
    }

    @Test
    fun theSeededCategoriesSatisfyTheirOwnUniquenessIndex() = runTest {
        // If the seed ever carried a duplicate the insert would abort onCreate, so this pins the
        // data as well as the statements.
        val keys = categories().map { Triple(it.kind, it.parentId, it.name.lowercase()) }

        assertEquals("the seed contains a duplicate sibling name", keys.size, keys.toSet().size)
    }

    private val sql get() = db.openHelper.writableDatabase

    /** The categories every install carries for interest, tax, fees and written-off debt. */
    private val preMade = listOf(
        listOf("Interest", "INCOME"),
        listOf("Interest", "EXPENSE"),
        listOf("Tax", "EXPENSE"),
        listOf("Fees", "EXPENSE"),
        listOf("Lost debt", "EXPENSE"),
        listOf("Debt forgiven", "INCOME"),
    )

    @Test
    fun aFreshInstallHasEachPreMadeCategoryExactlyOnceAtTopLevel() = runTest {
        categories()

        preMade.forEach { (name, kind) ->
            assertEquals(
                "$kind $name",
                "1",
                sql.scalar(
                    "SELECT COUNT(*) FROM categories WHERE name = ? AND kind = ? AND parentId = $TOP_LEVEL_PARENT_ID",
                    name, kind,
                ),
            )
        }
    }

    @Test
    fun aFreshInstallBindsFiveRolesToTheirPreMadeCategories() = runTest {
        categories()

        val boundTo = sql.textRows(
            "SELECT r.role, c.name, c.kind FROM category_roles r JOIN categories c ON c.id = r.categoryId",
        ).associate { it[0] to listOf(it[1], it[2]) }

        assertEquals(
            mapOf(
                "INTEREST_IN" to listOf("Interest", "INCOME"),
                "INTEREST_OUT" to listOf("Interest", "EXPENSE"),
                "FEES" to listOf("Fees", "EXPENSE"),
                "LOST_DEBT" to listOf("Lost debt", "EXPENSE"),
                "DEBT_FORGIVEN" to listOf("Debt forgiven", "INCOME"),
            ),
            boundTo,
        )
        assertEquals("exactly five roles, no extra rows", "5", sql.scalar("SELECT COUNT(*) FROM category_roles"))
    }

    @Test
    fun aFreshInstallHasNoUnreadableRowsAndNoEntries() = runTest {
        categories()

        assertEquals("0", sql.scalar("SELECT COUNT(*) FROM legacy_unreadable_rows"))
        assertEquals("0", sql.scalar("SELECT COUNT(*) FROM entries"))
    }
}
