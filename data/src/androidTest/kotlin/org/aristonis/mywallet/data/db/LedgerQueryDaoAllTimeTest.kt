package org.aristonis.mywallet.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.data.repo.RoomLedgerQueries
import org.aristonis.mywallet.domain.model.DateRange
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Tracking over all time reads the legs first, because a date index buys nothing when every entry is
 * wanted, and a month still starts from the date index.
 *
 * Assumed names, which the implementation must provide in package `org.aristonis.mywallet.data.db`:
 * - `internal const val CATEGORY_LEGS_ALL_TIME_SQL`, beside `CATEGORY_LEGS_SQL`, with no parameters,
 *   selecting the same columns by the same names, that `RoomLedgerQueries.observeCategoryLegs` runs
 *   for `DateRange.ALL_TIME`;
 * - neither category-leg statement joins the `categories` table (the category kind is unused).
 */
@RunWith(AndroidJUnit4::class)
class LedgerQueryDaoAllTimeTest {

    private lateinit var db: WalletDatabase
    private lateinit var queries: RoomLedgerQueries

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WalletDatabase::class.java,
        ).allowMainThreadQueries().build()
        queries = RoomLedgerQueries(db.ledgerQueryDao())
    }

    @After
    fun tearDown() = db.close()

    private val sql get() = db.openHelper.writableDatabase

    private fun plan(statement: String, vararg args: Any?): List<String> =
        sql.query("EXPLAIN QUERY PLAN $statement", arrayOf(*args)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail")))
            }
        }

    private fun entry(id: Long, date: LocalDate, kind: String = "EXPENSE") {
        sql.execSQL("INSERT INTO entries (id, kind, date) VALUES (?, ?, ?)", arrayOf<Any?>(id, kind, date.toEpochDay()))
    }

    private fun categoryLeg(entryId: Long, amount: String, categoryId: Long, rate: String?) {
        sql.execSQL(
            "INSERT INTO entry_legs (entryId, role, position, categoryId, amount, currencyCode, rateToBase) " +
                "VALUES (?, 'MAIN', 0, ?, ?, 'EUR', ?)",
            arrayOf<Any?>(entryId, categoryId, amount, rate),
        )
    }

    private fun accountLeg(entryId: Long, amount: String) {
        sql.execSQL(
            "INSERT INTO entry_legs (entryId, role, position, accountId, amount, currencyCode) " +
                "VALUES (?, 'ACCOUNT', 0, 1, ?, 'EUR')",
            arrayOf<Any?>(entryId, amount),
        )
    }

    /** Entries in January, February and March 2026; the category of entry 3 has no row at all. */
    private fun seedThreeMonths() {
        entry(1, LocalDate.of(2026, 1, 5)); categoryLeg(1, "10.00", 7, "1.08"); accountLeg(1, "-10.00")
        entry(2, LocalDate.of(2026, 1, 31)); categoryLeg(2, "20.00", 7, null)
        entry(3, LocalDate.of(2026, 2, 1)); categoryLeg(3, "30.00", 999, "1.10")
        entry(4, LocalDate.of(2026, 2, 28)); categoryLeg(4, "40.00", 8, "1.10"); accountLeg(4, "-40.00")
        entry(5, LocalDate.of(2026, 3, 15)); categoryLeg(5, "50.00", 8, null)
    }

    private val sharedColumns = listOf(
        "entryId", "date", "entryKind", "role", "categoryId", "subCategoryId", "amount", "currencyCode", "rateToBase",
    )

    private fun rowsOf(statement: String, vararg args: Any?): Set<List<String?>> =
        sql.query(statement, arrayOf(*args)).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    add(sharedColumns.map { name ->
                        val index = cursor.getColumnIndexOrThrow(name)
                        if (cursor.isNull(index)) null else cursor.getString(index)
                    })
                }
            }
        }

    @Test
    fun theAllTimeReadStartsWithAScanOfTheLegsAndSeeksEntriesByPrimaryKey() {
        val lines = plan(CATEGORY_LEGS_ALL_TIME_SQL)

        val detail = "query plan was: $lines"
        assertTrue(detail, Regex("^SCAN (TABLE )?(entry_legs( AS)? )?l\\b").containsMatchIn(lines.first()))
        assertTrue(detail, lines.any { Regex("^SEARCH (TABLE )?(entries( AS)? )?e\\b.*INTEGER PRIMARY KEY|^SEARCH (TABLE )?entries\\b.*INTEGER PRIMARY KEY").containsMatchIn(it) })
        assertFalse(detail, lines.any { "index_entries_date" in it })
        assertFalse(detail, lines.any { "TEMP B-TREE" in it })
    }

    @Test
    fun aMonthReadStillStartsFromTheDateIndexWithoutATempTree() {
        val lines = plan(CATEGORY_LEGS_SQL, LocalDate.of(2026, 2, 1).toEpochDay(), LocalDate.of(2026, 2, 28).toEpochDay())

        val detail = "query plan was: $lines"
        assertTrue(detail, lines.first().contains("index_entries_date"))
        assertFalse(detail, lines.any { "TEMP B-TREE" in it })
    }

    @Test
    fun neitherCategoryLegReadJoinsTheCategoriesTable() {
        val categories = Regex("\\bcategories\\b", RegexOption.IGNORE_CASE)

        assertFalse("all time still joins categories", categories.containsMatchIn(CATEGORY_LEGS_ALL_TIME_SQL))
        assertFalse("the month read still joins categories", categories.containsMatchIn(CATEGORY_LEGS_SQL))
    }

    @Test
    fun theAllTimeStatementReturnsTheMonthByMonthRowsAsASet() {
        seedThreeMonths()
        val months = listOf(
            LocalDate.of(2026, 1, 1) to LocalDate.of(2026, 1, 31),
            LocalDate.of(2026, 2, 1) to LocalDate.of(2026, 2, 28),
            LocalDate.of(2026, 3, 1) to LocalDate.of(2026, 3, 31),
        )
        val unionOfMonths = months.flatMap { (start, end) ->
            rowsOf(CATEGORY_LEGS_SQL, start.toEpochDay(), end.toEpochDay())
        }.toSet()

        val allTime = rowsOf(CATEGORY_LEGS_ALL_TIME_SQL)

        assertEquals(5, allTime.size)
        assertEquals(unionOfMonths, allTime)
    }

    @Test
    fun allTimeCategoryLegsFromTheRepositoryEqualTheUnionOfItsMonths() = runTest {
        seedThreeMonths()
        val months = listOf(
            DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
            DateRange(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)),
            DateRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)),
        )
        val unionOfMonths = months.flatMap { queries.observeCategoryLegs(it).first() }

        val allTime = queries.observeCategoryLegs(DateRange.ALL_TIME).first()

        assertEquals(unionOfMonths.toSet(), allTime.toSet())
        assertEquals(unionOfMonths.size, allTime.size)
        assertEquals("a leg on a category with no row still counts", 1, allTime.count { it.categoryId == 999L })
    }
}
