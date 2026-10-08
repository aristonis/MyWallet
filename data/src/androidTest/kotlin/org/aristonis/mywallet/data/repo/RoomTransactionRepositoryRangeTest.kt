package org.aristonis.mywallet.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * A ranged read is filtered by SQLite, not in Kotlin, so its bounds and its order are whatever the
 * query says. These run against the real database because that is the only place the query lives.
 */
@RunWith(AndroidJUnit4::class)
class RoomTransactionRepositoryRangeTest {

    private lateinit var db: WalletDatabase
    private lateinit var repository: RoomTransactionRepository

    private val from = LocalDate.of(2026, 8, 3)
    private val to = LocalDate.of(2026, 8, 17)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WalletDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomTransactionRepository(db.transactionDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun expenseOn(date: LocalDate): Long = repository.add(
        Transaction.Expense(accountId = 1, amount = Money.of("1", "USD"), categoryId = 1, date = date),
    )

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

    @Test
    fun rangeQueryUsesTheDateIndex() {
        val plan = db.openHelper.readableDatabase.query(
            "EXPLAIN QUERY PLAN SELECT * FROM transactions WHERE date BETWEEN 0 AND 1 ORDER BY date DESC, id DESC",
        ).use { cursor ->
            buildString { while (cursor.moveToNext()) append(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
        }

        assertTrue("query plan was: $plan", plan.contains("index_transactions_date"))
        // The id is the rowid, so the index already orders same-day rows: no temporary sort.
        assertFalse("query plan was: $plan", plan.contains("TEMP B-TREE"))
    }
}
