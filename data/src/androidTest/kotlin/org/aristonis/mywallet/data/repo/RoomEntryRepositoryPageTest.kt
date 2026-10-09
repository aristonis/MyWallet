package org.aristonis.mywallet.data.repo

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.EntryKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * The paged read of the entry list against the real database: each page is a keyset read on
 * (date desc, id desc) over the date index, so a page costs its own size however deep it is, and
 * the page after a key returns exactly the next keys.
 *
 * Assumed API, which the implementation must provide:
 * `EntryRepository.observePage(range: DateRange, after: EntryKey?, limit: Int): Flow<List<Transaction>>`
 * on [RoomEntryRepository], with `EntryKey(date: LocalDate, id: Long)` in `org.aristonis.mywallet.domain.port`.
 * The page reads entries strictly after `after`, newest first, at most `limit`, and re-emits when an
 * entry changes.
 */
@RunWith(AndroidJUnit4::class)
class RoomEntryRepositoryPageTest {

    private lateinit var db: WalletDatabase
    private lateinit var repository: RoomEntryRepository

    private val day = LocalDate.of(2026, 8, 10)

    /** Every statement the database ran with its bound values, so the real page query can be inspected. */
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
        db.openHelper.writableDatabase.execSQL("INSERT INTO categories (id, name, kind, parentId) VALUES (3, 'Food', 'EXPENSE', 0)")
    }

    @After
    fun tearDown() = db.close()

    private val sql get() = db.openHelper.writableDatabase

    private fun expense(date: LocalDate) = Transaction.Expense(
        accountId = 1, amount = Money.of("12.50", "USD"), categoryId = 3, date = date, note = "lunch",
    )

    private suspend fun add(date: LocalDate): Long = repository.insert(expense(date))

    private fun keyOf(tx: Transaction) = EntryKey(tx.date, tx.id)

    private suspend fun page(after: EntryKey?, limit: Int, range: DateRange = DateRange.ALL_TIME) =
        repository.observePage(range, after, limit).first()

    @Test
    fun theFirstPageIsTheNewestEntriesUpToTheLimit() = runTest {
        val ids = (1L..5L).map { add(day.plusDays(it)) }

        val page = page(after = null, limit = 3)

        assertEquals(ids.reversed().take(3), page.map { it.id })
    }

    @Test
    fun theNextPageReturnsExactlyTheNextKeys() = runTest {
        val ids = (1L..7L).map { add(day.plusDays(it)) }.reversed()
        val first = page(null, 3)

        val second = page(keyOf(first.last()), 3)
        val third = page(keyOf(second.last()), 3)

        assertEquals(ids.subList(3, 6), second.map { it.id })
        assertEquals(ids.subList(6, 7), third.map { it.id })
    }

    @Test
    fun entriesSharingADateAreSplitByIdAcrossAPageBoundary() = runTest {
        val sameDay = (1..6).map { add(day) }
        val older = add(day.minusDays(1))
        val first = page(null, 4)

        val second = page(keyOf(first.last()), 4)

        assertEquals(sameDay.reversed().take(4), first.map { it.id })
        assertEquals(sameDay.reversed().drop(4) + older, second.map { it.id })
    }

    @Test
    fun theLastPageIsShorterThanTheLimitAndTheOneAfterItIsEmpty() = runTest {
        (1L..4L).forEach { add(day.plusDays(it)) }
        val first = page(null, 3)
        val last = page(keyOf(first.last()), 3)

        val beyond = page(keyOf(last.last()), 3)

        assertEquals(1, last.size)
        assertTrue(beyond.isEmpty())
    }

    @Test
    fun aPageStaysInsideTheRange() = runTest {
        add(LocalDate.of(2026, 6, 30))
        val first = add(LocalDate.of(2026, 7, 1))
        val last = add(LocalDate.of(2026, 7, 31))
        add(LocalDate.of(2026, 8, 1))

        val page = page(null, 10, DateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))

        assertEquals(listOf(last, first), page.map { it.id })
    }

    @Test
    fun pagesWalkedToTheEndEqualTheWholeRangeRead() = runTest {
        (1L..23L).forEach { add(day.plusDays(it % 4)) }
        val walked = mutableListOf<Transaction>()

        var after: EntryKey? = null
        do {
            val next = page(after, 5)
            walked += next
            after = next.lastOrNull()?.let(::keyOf)
        } while (next.size == 5)

        assertEquals(repository.observeBetween(DateRange.ALL_TIME).first(), walked)
    }

    @Test
    fun aKeyWhoseEntryWasDeletedStillMarksWhereToContinue() = runTest {
        val ids = (1..5).map { add(day) }
        repository.delete(ids[2])

        val page = page(EntryKey(day, ids[2]), 10)

        assertEquals(listOf(ids[1], ids[0]), page.map { it.id })
    }

    @Test
    fun aPageReEmitsWhenAnEntryIsInserted() = runBlocking {
        add(day)
        val twoEntries = async(Dispatchers.Default) {
            withTimeout(WAIT_MS) { repository.observePage(DateRange.ALL_TIME, null, 3).first { it.size == 2 } }
        }

        add(day.plusDays(1))

        assertEquals(2, twoEntries.await().size)
    }

    @Test
    fun thePageQueryUsesTheDateIndexWithNoTemporarySortAndNoOffset() = runTest {
        (1L..30L).forEach { add(day.plusDays(it)) }
        val first = page(null, 10)
        executed.clear()

        page(keyOf(first.last()), 10)
        val paged = executed.filter { it.first.contains("LIMIT") && it.first.contains("FROM entries") }

        assertTrue("no paged statement was run: ${executed.map { it.first }}", paged.isNotEmpty())
        paged.forEach { (statement, args) ->
            assertFalse("a page must not skip rows with OFFSET: $statement", statement.contains("OFFSET", ignoreCase = true))
            val plan = planOf(statement, args)
            assertFalse("query plan was: $plan", plan.contains("USE TEMP B-TREE"))
        }
        val headerPlans = paged.map { planOf(it.first, it.second) }
        assertTrue("query plan was: $headerPlans", headerPlans.any { it.contains("index_entries_date") })
    }

    private fun planOf(statement: String, args: List<Any?>): String =
        sql.query("EXPLAIN QUERY PLAN $statement", args.toTypedArray()).use { cursor ->
            buildString { while (cursor.moveToNext()) appendLine(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
        }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
