package org.aristonis.mywallet.data.repo

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aristonis.mywallet.data.db.CategoryEntity
import org.aristonis.mywallet.data.db.DebtEntity
import org.aristonis.mywallet.data.db.DeductionItemEntity
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
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
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * The account detail page's entry read against the real database. The account filter must run in
 * storage: a page of the global list filtered afterwards can drop older matches, so every case here
 * puts other accounts' entries between the ones asked for.
 */
@RunWith(AndroidJUnit4::class)
class RoomAccountEntryPageTest {

    private lateinit var db: WalletDatabase
    private lateinit var repository: RoomEntryRepository

    private val day = LocalDate.of(2026, 8, 10)
    private val mine = 1L
    private val other = 2L

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
        sql.execSQL("INSERT INTO categories (id, name, kind, parentId) VALUES (3, 'Food', 'EXPENSE', 0)")
        runBlocking {
            db.categoryDao().insertAll(
                listOf(
                    CategoryEntity(id = SALARY, name = "Salary", kind = "INCOME"),
                    CategoryEntity(id = TAX, name = "Tax", kind = "EXPENSE"),
                    CategoryEntity(id = FAMILY, name = "Family", kind = "EXPENSE"),
                ),
            )
            db.deductionDao().insertItems(
                listOf(
                    DeductionItemEntity(
                        id = ITEM_TAX, name = "Tax", nameKey = "tax", mode = "FIXED", value = "10",
                        valueCurrencyCode = "USD", timing = "NOW", expenseCategoryId = TAX, enabled = true, sortOrder = 0,
                    ),
                    DeductionItemEntity(
                        id = ITEM_FAMILY, name = "Family", nameKey = "family", mode = "FIXED", value = "10",
                        valueCurrencyCode = "USD", timing = "LATER", expenseCategoryId = FAMILY, enabled = true, sortOrder = 1,
                    ),
                ),
            )
            db.debtDao().insert(
                DebtEntity(
                    id = DEBT, counterparty = "Family", counterpartyKey = "family",
                    side = "I_OWE", currencyCode = "USD",
                ),
            )
        }
    }

    @After
    fun tearDown() = db.close()

    private val sql get() = db.openHelper.writableDatabase

    private suspend fun expense(account: Long, date: LocalDate): Long = repository.insert(
        Transaction.Expense(accountId = account, amount = Money.of("5.00", "USD"), categoryId = 3, date = date),
    )

    private suspend fun transfer(from: Long, to: Long, date: LocalDate): Long = repository.insert(
        Transaction.Transfer(
            sourceAccountId = from, destAccountId = to,
            sourceAmount = Money.of("10.00", "USD"), destAmount = Money.of("10.00", "USD"),
            rateUsed = BigDecimal.ONE, date = date,
        ),
    )

    private fun nowLine(position: Int) = DeductionLine(
        itemId = ITEM_TAX, position = position, name = "Tax", mode = DeductionMode.FIXED,
        value = BigDecimal("20"), valueCurrencyCode = "USD", timing = DeductionTiming.NOW,
        categoryId = TAX, amount = Money.of("20", "USD"),
    )

    private fun laterLine(position: Int) = DeductionLine(
        itemId = ITEM_FAMILY, position = position, name = "Family", mode = DeductionMode.FIXED,
        value = BigDecimal("30"), valueCurrencyCode = "USD", timing = DeductionTiming.LATER,
        categoryId = FAMILY, amount = Money.of("30", "USD"), debtId = DEBT,
    )

    private suspend fun income(account: Long, date: LocalDate, deductions: List<DeductionLine>): Long = repository.insert(
        Transaction.Income(
            accountId = account, amount = Money.of("1000", "USD"), categoryId = SALARY, date = date,
            rateToBase = BigDecimal.ONE, deductions = deductions,
        ),
    )

    private fun keyOf(tx: Transaction) = EntryKey(tx.date, tx.id)

    private suspend fun page(
        after: EntryKey?,
        limit: Int,
        range: DateRange = DateRange.ALL_TIME,
        account: Long = mine,
    ) = repository.observeAccountPage(account, range, after, limit).first()

    @Test
    fun onlyEntriesWithALegOnTheAccountAreReturnedEvenWhenOthersInterleave() = runTest {
        val first = expense(mine, day)
        expense(other, day.plusDays(1))
        val second = expense(mine, day.plusDays(2))
        expense(other, day.plusDays(3))

        val page = page(null, 10)

        assertEquals(listOf(second, first), page.map { it.id })
    }

    @Test
    fun anOlderMatchBehindManyOtherAccountEntriesIsNotHiddenByThePageLimit() = runTest {
        val oldest = expense(mine, day)
        (1L..30L).forEach { expense(other, day.plusDays(it)) }

        val page = page(null, 2)

        assertEquals(listOf(oldest), page.map { it.id })
    }

    @Test
    fun theRangeIsInclusiveAtBothEnds() = runTest {
        expense(mine, day.minusDays(1))
        val start = expense(mine, day)
        val end = expense(mine, day.plusDays(5))
        expense(mine, day.plusDays(6))

        val page = page(null, 10, DateRange(day, day.plusDays(5)))

        assertEquals(listOf(end, start), page.map { it.id })
    }

    @Test
    fun entriesSharingADateAreSplitByIdAcrossAPageBoundary() = runTest {
        val sameDay = (1..5).map { expense(mine, day) }
        val older = expense(mine, day.minusDays(1))
        val first = page(null, 3)

        val second = page(keyOf(first.last()), 3)

        assertEquals(sameDay.reversed().take(3), first.map { it.id })
        assertEquals(sameDay.reversed().drop(3) + older, second.map { it.id })
    }

    @Test
    fun aPageHoldsAtMostTheLimit() = runTest {
        (1L..9L).forEach { expense(mine, day.plusDays(it)) }

        assertEquals(4, page(null, 4).size)
    }

    @Test
    fun aTransferBetweenTwoAccountsAppearsOnBothAccountsWithBothLegs() = runTest {
        val id = transfer(mine, other, day)

        val onMine = page(null, 10, account = mine)
        val onOther = page(null, 10, account = other)

        assertEquals(listOf(id), onMine.map { it.id })
        assertEquals(listOf(id), onOther.map { it.id })
        val read = onMine.single() as Transaction.Transfer
        assertEquals(mine, read.sourceAccountId)
        assertEquals(other, read.destAccountId)
    }

    @Test
    fun anEntryWithTwoLegsOnTheSameAccountIsSelectedOnce() = runTest {
        val id = expense(mine, day)
        // No recipe writes two legs on one account, so the second one is added in storage: the
        // filter has to stay one row per entry whatever shape the legs have.
        sql.execSQL(
            "INSERT INTO entry_legs (entryId, role, position, accountId, amount, currencyCode) " +
                "VALUES ($id, 'FEE', 9, $mine, '-1.00', 'USD')",
        )

        val headers = db.entryDao().filteredHeadersPage(day, day, Long.MAX_VALUE, 10, emptyList(), true, listOf(mine), false, emptyList(), true)

        assertEquals(listOf(id), headers.map { it.id })
    }

    @Test
    fun aPageContinuesAfterAKeyWhoseEntryWasDeleted() = runTest {
        val ids = (1..4).map { expense(mine, day) }
        repository.delete(ids[2])

        val page = page(EntryKey(day, ids[2]), 10)

        assertEquals(listOf(ids[1], ids[0]), page.map { it.id })
    }

    @Test
    fun anEntryOfAnUnknownKindOnTheAccountFailsTheReadAsUnreadable() = runTest {
        sql.execSQL("INSERT INTO entries (id, kind, date) VALUES (50, 'LEGACY_UNREADABLE', 20454)")
        sql.execSQL(
            "INSERT INTO entry_legs (entryId, role, position, accountId, amount, currencyCode) " +
                "VALUES (50, 'MAIN', 0, $mine, '1.00', 'USD')",
        )

        var thrown: Throwable? = null
        try {
            page(null, 10)
        } catch (e: WalletException.UnreadableRow) {
            thrown = e
        }

        assertTrue("expected UnreadableRow, got $thrown", thrown is WalletException.UnreadableRow)
        assertEquals(50L, (thrown as WalletException.UnreadableRow).id)
    }

    @Test
    fun thePageQueryFiltersByTheAccountIndexWithALimitAndNoTemporarySortOrOffset() = runTest {
        (1L..20L).forEach { expense(mine, day.plusDays(it)); expense(other, day.plusDays(it)) }
        val first = page(null, 5)
        executed.clear()

        page(keyOf(first.last()), 5)
        val paged = executed.filter { it.first.contains("LIMIT") && it.first.contains("entry_legs") }

        assertTrue("no account page statement was run: ${executed.map { it.first }}", paged.isNotEmpty())
        paged.forEach { (statement, args) ->
            assertFalse("a page must not skip rows with OFFSET: $statement", statement.contains("OFFSET", ignoreCase = true))
            val plan = planOf(statement, args)
            assertFalse("query plan was: $plan", plan.contains("USE TEMP B-TREE"))
            assertTrue("the account leg index must serve the filter, plan was: $plan", plan.contains("index_entry_legs_accountId_amount"))
        }
    }

    @Test
    fun deductionLinesComeBackOnTheirOwnIncomeAcrossAPageBoundary() = runTest {
        val older = income(mine, day, listOf(nowLine(0)))
        income(other, day.plusDays(1), listOf(nowLine(0), laterLine(1)))
        val middle = income(mine, day.plusDays(2), listOf(nowLine(0), laterLine(1)))
        income(other, day.plusDays(3), listOf(nowLine(0)))
        val newest = income(mine, day.plusDays(4), emptyList())

        val first = page(null, 2)
        val second = page(keyOf(first.last()), 2)

        assertEquals(listOf(newest, middle), first.map { it.id })
        assertEquals(listOf(older), second.map { it.id })
        assertEquals(repository.findById(middle), first[1])
        assertEquals(repository.findById(older), second[0])
        assertEquals(2, (first[1] as Transaction.Income).deductions.size)
        assertEquals(1, (second[0] as Transaction.Income).deductions.size)
    }

    @Test
    fun aKeyDatedAfterTheRangeEndStartsFromTheTop() = runTest {
        val newest = expense(mine, day.plusDays(2))
        val older = expense(mine, day)

        val page = page(EntryKey(day.plusDays(30), 999), 10, DateRange(day, day.plusDays(2)))

        assertEquals(listOf(newest, older), page.map { it.id })
    }

    @Test
    fun aKeyDatedBeforeTheRangeStartLeavesNothingToRead() = runTest {
        expense(mine, day)

        val page = page(EntryKey(day.minusDays(30), 999), 10, DateRange(day, day.plusDays(2)))

        assertTrue(page.isEmpty())
    }

    @Test
    fun aPageReEmitsWhenAnEntryMovesOntoTheAccount() = runBlocking {
        val moving = expense(other, day)
        val onMine = async(Dispatchers.Default) {
            withTimeout(WAIT_MS) {
                repository.observeAccountPage(mine, DateRange.ALL_TIME, null, 5).first { it.isNotEmpty() }
            }
        }

        repository.replace(
            Transaction.Expense(
                id = moving, accountId = mine, amount = Money.of("5.00", "USD"), categoryId = 3, date = day,
            ),
        )

        assertEquals(listOf(moving), onMine.await().map { it.id })
    }

    @Test
    fun aPageLargerThanTheBindVariableLimitStillReadsEveryEntry() = runTest {
        val ids = (1..BIND_LIMIT_PLUS).map { expense(mine, day.plusDays((it % 50).toLong())) }

        val page = page(null, BIND_LIMIT_PLUS)

        assertEquals(ids.size, page.size)
        assertEquals(ids.toSet(), page.map { it.id }.toSet())
    }

    @Test
    fun legsAndDeductionLinesAreReadByEntryNotByRebuildingTheAccountList() = runTest {
        (1L..20L).forEach { income(mine, day.plusDays(it), listOf(nowLine(0))); expense(other, day.plusDays(it)) }
        val first = page(null, 5)
        executed.clear()

        page(keyOf(first.last()), 5)
        val followUps = executed.filter { (statement, _) ->
            statement.contains("income_deduction_lines") || statement.contains("LEFT JOIN debts")
        }

        assertTrue("no leg or deduction statement was run: ${executed.map { it.first }}", followUps.isNotEmpty())
        followUps.forEach { (statement, args) ->
            assertFalse("only the header read may look at the account's legs: $statement", statement.contains("accountId = ?"))
            val plan = planOf(statement, args)
            assertFalse("query plan was: $plan", plan.contains("USE TEMP B-TREE"))
        }
    }

    private fun planOf(statement: String, args: List<Any?>): String =
        sql.query("EXPLAIN QUERY PLAN $statement", args.toTypedArray()).use { cursor ->
            buildString { while (cursor.moveToNext()) appendLine(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
        }

    private companion object {
        const val SALARY = 10L
        const val TAX = 11L
        const val FAMILY = 12L
        const val ITEM_TAX = 1L
        const val ITEM_FAMILY = 2L
        const val DEBT = 1L
        const val WAIT_MS = 5_000L

        /** Past SQLite's 999 bound-variable limit on older Android releases. */
        const val BIND_LIMIT_PLUS = 1_100
    }
}
