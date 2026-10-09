package org.aristonis.mywallet.data.repo

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.data.db.scalar
import org.aristonis.mywallet.data.db.textRows
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.TransactionRepository
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * Entries are stored as a header plus legs, so insert, replace and delete each touch two tables and
 * a ranged read is a query over the header table. These run against the real database because the
 * contract is what the rows look like afterwards, and which rows an id can reach.
 *
 * Assumed constructor, which the implementation must provide:
 * `RoomEntryRepository(database: WalletDatabase, dao: EntryDao)`, with `WalletDatabase.entryDao()`.
 * It implements the `EntryRepository` port and, until the old port is retired, `TransactionRepository`.
 */
@RunWith(AndroidJUnit4::class)
class RoomEntryRepositoryTest {

    private lateinit var db: WalletDatabase
    private lateinit var repository: RoomEntryRepository

    private val from = LocalDate.of(2026, 8, 3)
    private val to = LocalDate.of(2026, 8, 17)

    /** Every statement the database ran with its bound values, so a test can inspect the real query text. */
    private val executed = CopyOnWriteArrayList<Pair<String, List<Any?>>>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WalletDatabase::class.java,
        ).allowMainThreadQueries()
            .setQueryCallback(RoomDatabase.QueryCallback { sql, args -> executed += sql to args }, Executor { it.run() })
            .build()
        repository = RoomEntryRepository(db, db.entryDao())
        // sub-category ids carry a foreign key, so the ones the tests file under must exist
        listOf(
            arrayOf<Any?>(3, "Food", 0), arrayOf<Any?>(4, "Lunch", 3),
            arrayOf<Any?>(5, "Transport", 0), arrayOf<Any?>(6, "Taxi", 5),
        ).forEach {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO categories (id, name, kind, parentId) VALUES (?, ?, 'EXPENSE', ?)", it,
            )
        }
    }

    @After
    fun tearDown() = db.close()

    private val sql get() = db.openHelper.writableDatabase

    private fun expense(
        id: Long = 0,
        date: LocalDate = from,
        amount: String = "12.50",
        categoryId: Long = 3,
        subCategoryId: Long? = null,
        rate: String? = "1",
    ) = Transaction.Expense(
        id = id, accountId = 1, amount = Money.of(amount, "USD"), categoryId = categoryId,
        subCategoryId = subCategoryId, date = date, note = "lunch", rateToBase = rate?.let(::BigDecimal),
    )

    private suspend fun expenseOn(date: LocalDate): Long = repository.insert(expense(date = date))

    private fun legCount(entryId: Long): String? = sql.scalar("SELECT COUNT(*) FROM entry_legs WHERE entryId = ?", entryId)

    @Test
    fun insertReturnsTheNewIdAndTheEntryReadsBackUnchanged() = runTest {
        val income = Transaction.Income(
            accountId = 1, amount = Money.of("2500.00", "USD"), categoryId = 1, date = from, note = "Salary",
            rateToBase = BigDecimal.ONE,
        )
        val transfer = Transaction.Transfer(
            sourceAccountId = 1, destAccountId = 2, sourceAmount = Money.of("100.00", "USD"),
            destAmount = Money.of("92.59", "EUR"), rateUsed = BigDecimal("0.9259"), date = to,
        )

        val incomeId = repository.insert(income)
        val expenseId = repository.insert(expense(subCategoryId = 4))
        val transferId = repository.insert(transfer)

        assertEquals(3, setOf(incomeId, expenseId, transferId).size)
        assertEquals(income.copy(id = incomeId), repository.findById(incomeId))
        assertEquals(expense(id = expenseId, subCategoryId = 4), repository.findById(expenseId))
        assertEquals(transfer.copy(id = transferId), repository.findById(transferId))
    }

    @Test
    fun insertStoresTwoLegsWithTheAmountTextAndScaleItWasGiven() = runTest {
        val id = repository.insert(expense(amount = "12.50"))

        assertEquals(
            listOf(
                listOf("ACCOUNT", "1", null, "-12.50", "USD", null),
                listOf("MAIN", null, "3", "12.50", "USD", "1"),
            ),
            sql.textRows(
                "SELECT role, accountId, categoryId, amount, currencyCode, rateToBase FROM entry_legs " +
                    "WHERE entryId = ? ORDER BY role",
                id,
            ),
        )
    }

    @Test
    fun aTransferStoresItsPairRateOnTheHeaderWithTheScaleItWasGiven() = runTest {
        val id = repository.insert(
            Transaction.Transfer(
                sourceAccountId = 1, destAccountId = 2, sourceAmount = Money.of("20.000", "USD"),
                destAmount = Money.of("18.40", "EUR"), rateUsed = BigDecimal("0.920000"), date = from,
            ),
        )

        assertEquals("0.920000", sql.scalar("SELECT pairRate FROM entries WHERE id = ?", id))
        assertEquals(
            listOf(listOf("DEST", "18.40"), listOf("SOURCE", "-20.000")),
            sql.textRows("SELECT role, amount FROM entry_legs WHERE entryId = ? ORDER BY role", id),
        )
    }

    @Test
    fun replaceRewritesTheHeaderAndTheLegsInPlaceUnderTheSameId() = runTest {
        val id = repository.insert(expense(amount = "12.50", categoryId = 3, date = from))
        val other = repository.insert(expense(amount = "99.00"))

        repository.replace(expense(id = id, amount = "20.00", categoryId = 5, subCategoryId = 6, date = to, rate = "2"))

        assertEquals(expense(id = id, amount = "20.00", categoryId = 5, subCategoryId = 6, date = to, rate = "2"), repository.findById(id))
        assertEquals("the edit leaves exactly two legs, no leftovers", "2", legCount(id))
        assertEquals("1", sql.scalar("SELECT COUNT(*) FROM entries WHERE id = ?", id))
        assertEquals("another entry is untouched", "99.00", sql.scalar("SELECT amount FROM entry_legs WHERE entryId = ? AND role = 'MAIN'", other))
    }

    @Test
    fun deleteRemovesTheEntryAndCascadesToItsLegsOnly() = runTest {
        val doomed = repository.insert(expense())
        val kept = repository.insert(expense())

        repository.delete(doomed)

        assertNull(repository.findById(doomed))
        assertEquals("0", legCount(doomed))
        assertNotNull(repository.findById(kept))
        assertEquals("2", legCount(kept))
    }

    @Test
    fun findByIdOfAnIdThatDoesNotExistIsNull() = runTest {
        assertNull(repository.findById(404))
    }

    @Test
    fun insertAtAFreeIdKeepsThatIdSoAnUndoPutsTheEntryBack() = runTest {
        val id = repository.insert(expense(amount = "12.50"))
        repository.delete(id)

        val restored = repository.insert(expense(id = id, amount = "12.50"))

        assertEquals(id, restored)
        assertEquals(expense(id = id, amount = "12.50"), repository.findById(id))
        assertEquals("2", legCount(id))
    }

    @Test
    fun insertAtATakenIdAbortsAndLeavesTheExistingEntryAndItsLegsAsTheyWere() = runTest {
        val id = repository.insert(expense(amount = "12.50"))

        var thrown: Throwable? = null
        try {
            repository.insert(expense(id = id, amount = "777.00", categoryId = 9))
        } catch (e: Throwable) {
            thrown = e
        }

        assertNotNull("a taken id must abort, not overwrite", thrown)
        assertTrue("expected EntryIdTaken, got $thrown", thrown is WalletException.EntryIdTaken)
        assertEquals(id, (thrown as WalletException.EntryIdTaken).id)
        assertEquals(expense(id = id, amount = "12.50"), repository.findById(id))
        assertEquals("2", legCount(id))
        assertEquals("1", sql.scalar("SELECT COUNT(*) FROM entries"))
    }

    @Test
    fun anEntryOfAKindNoRecipeReadsFailsTheReadAsUnreadable() = runTest {
        sql.execSQL("INSERT INTO entries (id, kind, date) VALUES (50, 'LEGACY_UNREADABLE', 20454)")

        var thrown: Throwable? = null
        try {
            repository.findById(50)
        } catch (e: WalletException.UnreadableRow) {
            thrown = e
        }

        assertTrue("expected UnreadableRow, got $thrown", thrown is WalletException.UnreadableRow)
        assertEquals(50L, (thrown as WalletException.UnreadableRow).id)
        assertEquals("LEGACY_UNREADABLE", thrown.kind)
    }

    @Test
    fun betweenIsInclusive() = runTest {
        expenseOn(from.minusDays(1))
        val first = expenseOn(from)
        val last = expenseOn(to)
        expenseOn(to.plusDays(1))

        val ids = repository.observeBetween(DateRange(from, to)).first().map { it.id }.toSet()

        assertEquals(setOf(first, last), ids)
    }

    @Test
    fun returnsOnlyInRangeNewestFirst() = runTest {
        val older = expenseOn(LocalDate.of(2026, 8, 5))
        val newer = expenseOn(LocalDate.of(2026, 8, 12))
        val sameDayLater = expenseOn(LocalDate.of(2026, 8, 12))
        expenseOn(LocalDate.of(2026, 9, 1))

        val ids = repository.observeBetween(DateRange(from, to)).first().map { it.id }

        // Same day falls back to id, newest first, exactly as the full list orders it.
        assertEquals(listOf(sameDayLater, newer, older), ids)
    }

    @Test
    fun allTimeReturnsEverything() = runTest {
        expenseOn(LocalDate.of(1990, 1, 1))
        expenseOn(LocalDate.of(2026, 8, 10))
        expenseOn(LocalDate.of(2099, 12, 31))

        assertEquals(3, repository.observeBetween(DateRange.ALL_TIME).first().size)
    }

    /**
     * Runs EXPLAIN QUERY PLAN on the statement the range read really executes (captured from the
     * database, with its bound dates), not on a simplified copy of it. That statement joins the
     * legs and the debt side to the header, so the plan has to be checked on the join.
     */
    @Test
    fun rangeQueryUsesTheDateIndex() = runTest {
        repository.observeBetween(DateRange(from, to)).first()
        val (statement, args) = executed.last {
            it.first.contains("FROM entries") && it.first.contains("entry_legs") && it.first.contains("BETWEEN")
        }

        val plan = sql.query("EXPLAIN QUERY PLAN $statement", args.toTypedArray()).use { cursor ->
            buildString { while (cursor.moveToNext()) appendLine(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
        }

        assertTrue("query plan was: $plan", plan.contains("index_entries_date"))
        // The id is the rowid, so the index already orders same-day rows: no temporary sort.
        assertFalse("query plan was: $plan", plan.contains("USE TEMP B-TREE"))
    }

    // --- The 1.1.1 port keeps working over the new tables until its callers move. ---

    private val legacyPort: TransactionRepository get() = repository

    @Test
    fun addThroughTheOldPortInsertsAnEntryWithItsLegs() = runTest {
        val id = legacyPort.add(expense(amount = "8.00"))

        assertTrue(id > 0)
        assertEquals(expense(id = id, amount = "8.00"), legacyPort.findById(id))
        assertEquals("2", legCount(id))
    }

    @Test
    fun updateThroughTheOldPortReplacesTheEntry() = runTest {
        val id = legacyPort.add(expense(amount = "8.00"))

        legacyPort.update(expense(id = id, amount = "9.00", categoryId = 7))

        assertEquals(expense(id = id, amount = "9.00", categoryId = 7), legacyPort.findById(id))
        assertEquals("2", legCount(id))
    }

    @Test
    fun observeAllThroughTheOldPortIsEveryEntryNewestFirst() = runTest {
        val oldest = legacyPort.add(expense(date = LocalDate.of(2026, 1, 1)))
        val newest = legacyPort.add(expense(date = LocalDate.of(2026, 3, 1)))
        val middleLaterId = legacyPort.add(expense(date = LocalDate.of(2026, 2, 1)))

        assertEquals(listOf(newest, middleLaterId, oldest), legacyPort.observeAll().first().map { it.id })
    }
}
