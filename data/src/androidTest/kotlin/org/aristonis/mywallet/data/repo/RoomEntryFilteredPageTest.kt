package org.aristonis.mywallet.data.repo

import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import java.math.BigDecimal
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.model.TransactionFilter
import org.aristonis.mywallet.domain.model.TypeGroup
import org.aristonis.mywallet.domain.port.EntryKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.io.File
import android.content.Context

@RunWith(AndroidJUnit4::class)
class RoomEntryFilteredPageTest {
    private lateinit var db: WalletDatabase
    private lateinit var entries: RoomEntryRepository
    private val executed = CopyOnWriteArrayList<Pair<String, List<Any?>>>()
    private val day = LocalDate.of(2026, 10, 1)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WalletDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback(RoomDatabase.QueryCallback { sql, args -> executed += sql to args }, Executor { it.run() })
            .build()
        entries = RoomEntryRepository(db, db.entryDao())
        db.openHelper.writableDatabase.execSQL("INSERT INTO categories (id,name,kind,parentId) VALUES (101,'Bills','EXPENSE',0),(102,'Internet','EXPENSE',101)")
    }

    @After fun tearDown() = db.close()

    private fun expense(account: Long = 1, category: Long = 101, sub: Long? = null, date: LocalDate = day) =
        Transaction.Expense(accountId = account, amount = Money.of("10", "USD"), categoryId = category,
            subCategoryId = sub, date = date)

    @Test fun interleavedNonmatchesNeverShortenPagesOrLeakTheirLegs() = runTest {
        val expected = (1..7).map {
            val id = entries.insert(expense())
            entries.insert(expense(account = 2))
            id
        }.reversed()
        val filter = TransactionFilter(types = setOf(TypeGroup.EXPENSE), accountIds = setOf(1))
        val pages = mutableListOf<List<Transaction>>()
        var after: EntryKey? = null
        do {
            val page = entries.observePage(DateRange.ALL_TIME, after, 3, filter).first()
            pages += page
            after = page.lastOrNull()?.let { EntryKey(it.date, it.id) }
        } while (page.size == 3)
        assertEquals(listOf(3, 3, 1), pages.map { it.size })
        assertEquals(expected, pages.flatten().map { it.id })
        assertTrue(pages.flatten().all { (it as Transaction.Expense).accountId == 1L })
    }

    @Test fun accountAndCategoryIntersectAndParentIncludesChild() = runTest {
        val direct = entries.insert(expense())
        val child = entries.insert(expense(sub = 102))
        entries.insert(expense(account = 2, sub = 102))
        entries.insert(expense(category = 999))
        val parent = entries.observePage(DateRange.ALL_TIME, null, 20,
            TransactionFilter(accountIds = setOf(1), categoryIds = setOf(101))).first()
        val sub = entries.observePage(DateRange.ALL_TIME, null, 20,
            TransactionFilter(categoryIds = setOf(102))).first()
        assertEquals(listOf(child, direct), parent.map { it.id })
        assertEquals(2, sub.size)
    }

    @Test fun incomeMonthHasFullPagesAndExcludesBoundaryDates() = runTest {
        val ids = (1..5).map { index ->
            entries.insert(expense())
            entries.insert(Transaction.Income(accountId = 1, amount = Money.of("20", "USD"), categoryId = 201,
                date = day.plusDays(index.toLong())))
        }.reversed()
        entries.insert(Transaction.Income(accountId = 1, amount = Money.of("20", "USD"), categoryId = 201,
            date = day.minusDays(1)))
        val range = DateRange(day, day.plusDays(30))
        val first = entries.observePage(range, null, 3, TransactionFilter(types = setOf(TypeGroup.INCOME))).first()
        val second = entries.observePage(range, EntryKey(first.last().date, first.last().id), 3,
            TransactionFilter(types = setOf(TypeGroup.INCOME))).first()
        assertEquals(ids.take(3), first.map { it.id })
        assertEquals(ids.drop(3), second.map { it.id })
    }

    @Test fun accountReaderAndAllFilteredReaderKeepTheSamePageContract() = runTest {
        repeat(8) { entries.insert(expense(account = if (it % 2 == 0) 1 else 2)) }
        val expected = entries.observeAccountPage(1, DateRange.ALL_TIME, null, 3).first()
        val actual = entries.observePage(DateRange.ALL_TIME, null, 3, TransactionFilter(accountIds = setOf(1))).first()
        assertEquals(expected, actual)
        assertEquals(entries.observePage(DateRange.ALL_TIME, null, 3).first(),
            entries.observePage(DateRange.ALL_TIME, null, 3, TransactionFilter()).first())
    }

    @Test fun taxMatchesSalaryDeductionWithoutReclassifyingSalaryAsExpense() = runTest {
        db.openHelper.writableDatabase.execSQL("INSERT INTO deduction_items (id,name,nameKey,mode,value,valueCurrencyCode,timing,expenseCategoryId,enabled,sortOrder) VALUES (1,'Tax','tax','FIXED','10','USD','NOW',301,1,0)")
        val line = DeductionLine(itemId = 1, position = 0, name = "Tax", mode = DeductionMode.FIXED,
            value = BigDecimal.TEN, valueCurrencyCode = "USD", timing = DeductionTiming.NOW,
            categoryId = 301, amount = Money.of("10", "USD"))
        val id = entries.insert(Transaction.Income(accountId = 1, amount = Money.of("100", "USD"),
            categoryId = 201, deductions = listOf(line), date = day))
        val all = entries.observePage(DateRange.ALL_TIME, null, 10, TransactionFilter(categoryIds = setOf(301))).first()
        val expense = entries.observePage(DateRange.ALL_TIME, null, 10,
            TransactionFilter(types = setOf(TypeGroup.EXPENSE), categoryIds = setOf(301))).first()
        assertEquals(listOf(id), all.map { it.id })
        assertTrue(expense.isEmpty())
    }

    @Test fun writeOffChipComesFromStoredCategoryRoleRatherThanDebtSide() = runTest {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO debts (id,counterparty,counterpartyKey,side,currencyCode) VALUES (1,'A','a','OWED_TO_ME','USD'),(2,'B','b','I_OWE','USD')")
        val lost = entries.insert(Transaction.WriteOff(side = DebtSide.OWED_TO_ME, debtId = 1,
            amount = Money.of("10", "USD"), categoryId = 101, date = day))
        val forgiven = entries.insert(Transaction.WriteOff(side = DebtSide.I_OWE, debtId = 2,
            amount = Money.of("10", "USD"), categoryId = 201, date = day))
        val expenses = entries.observePage(DateRange.ALL_TIME, null, 10, TransactionFilter(types = setOf(TypeGroup.EXPENSE))).first()
        val incomes = entries.observePage(DateRange.ALL_TIME, null, 10, TransactionFilter(types = setOf(TypeGroup.INCOME))).first()
        val debts = entries.observePage(DateRange.ALL_TIME, null, 10, TransactionFilter(types = setOf(TypeGroup.DEBTS))).first()
        assertEquals(listOf(lost), expenses.map { it.id })
        assertEquals(listOf(forgiven), incomes.map { it.id })
        assertTrue(debts.isEmpty())
    }

    @Test fun feeAndPaidLaterCategoryLegsMatchWithoutChangingTheEntryChip() = runTest {
        db.openHelper.writableDatabase.execSQL("INSERT INTO debts (id,counterparty,counterpartyKey,side,currencyCode) VALUES (1,'Shop','shop','I_OWE','USD')")
        val expenseId = entries.insert(expense().copy(owed = Money.of("10", "USD"), owedDebtId = 1))
        val salaryId = entries.insert(Transaction.Income(accountId = 1, amount = Money.of("100", "USD"),
            categoryId = 201, fee = Money.of("2", "USD"), feeCategoryId = 401, date = day))
        val fees = entries.observePage(DateRange.ALL_TIME, null, 10, TransactionFilter(categoryIds = setOf(401))).first()
        val expenses = entries.observePage(DateRange.ALL_TIME, null, 10,
            TransactionFilter(types = setOf(TypeGroup.EXPENSE), categoryIds = setOf(101))).first()
        val debts = entries.observePage(DateRange.ALL_TIME, null, 10, TransactionFilter(types = setOf(TypeGroup.DEBTS))).first()
        assertEquals(listOf(salaryId), fees.map { it.id })
        assertEquals(listOf(expenseId), expenses.map { it.id })
        assertTrue(debts.isEmpty())
    }

    @Test fun multipleSelectionsUseOrWithinGroupsAndAndAcrossGroups() = runTest {
        val first = entries.insert(expense(account = 1, category = 101))
        val second = entries.insert(expense(account = 2, category = 101))
        val income = entries.insert(Transaction.Income(accountId = 1, amount = Money.of("20", "USD"),
            categoryId = 201, date = day))
        entries.insert(expense(account = 1, category = 401))
        val filter = TransactionFilter(types = setOf(TypeGroup.INCOME, TypeGroup.EXPENSE),
            accountIds = setOf(1L, 2L), categoryIds = setOf(101L, 201L))
        assertEquals(listOf(income, second, first),
            entries.observePage(DateRange.ALL_TIME, null, 10, filter).first().map { it.id })
        assertEquals(listOf(income, first), entries.observePage(DateRange.ALL_TIME, null, 10,
            filter.copy(accountIds = setOf(1L, 99L))).first().map { it.id })
    }

    @Test fun largeSelectionsStayBoundedAndMergePagesWithoutDuplicates() = runTest {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO categories (id,name,kind,parentId) VALUES (501,'Service','EXPENSE',101),(550,'Other','EXPENSE',0)",
        )
        val first = entries.insert(expense(account = 1, category = 101))
        val overlap = entries.insert(expense(account = 1, category = 101, sub = 501))
        val other = entries.insert(expense(account = 501, category = 550))
        val newest = entries.insert(expense(account = 1, category = 101, date = day.plusDays(1)))
        val filter = TransactionFilter(types = setOf(TypeGroup.INCOME, TypeGroup.EXPENSE),
            accountIds = (1L..650L).toSet(), categoryIds = (101L..600L).toSet())
        executed.clear()
        val page = entries.observePage(DateRange.ALL_TIME, null, 2, filter).first()
        assertEquals(listOf(newest, other), page.map { it.id })
        assertTrue(executed.filter { it.first.contains("LIMIT") && it.first.contains("FROM entries") }
            .all { it.second.size <= 999 })
        val next = entries.observePage(DateRange.ALL_TIME, EntryKey(page.last().date, page.last().id),
            2, filter).first()
        assertEquals(listOf(overlap, first), next.map { it.id })
        val last = next.last()
        assertTrue(entries.observePage(DateRange.ALL_TIME, EntryKey(last.date, last.id), 2, filter).first().isEmpty())
    }

    @Test fun everyFilterTermKeepsTheDateIndexWithoutTemporarySorting() = runTest {
        entries.insert(expense())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = File(context.filesDir, "filtered-query-plans.txt")
        output.writeText("")
        for (type in TypeGroup.entries) for ((account, category) in listOf(null to null, 1L to null, null to 101L, 1L to 101L)) {
            executed.clear()
            db.entryDao().filteredHeadersPage(day, day.plusDays(30), Long.MAX_VALUE, 3,
                listOf(type.name).filter { it != "ALL" }, type == TypeGroup.ALL,
                listOfNotNull(account), account == null, listOfNotNull(category), category == null)
            val (statement, args) = executed.last { it.first.contains("LIMIT") && it.first.contains("FROM entries") }
            val plan = db.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $statement", args.toTypedArray())
                .use { cursor -> buildString { while (cursor.moveToNext()) appendLine(cursor.getString(3)) } }
            output.appendText("type=$type account=$account category=$category\n$plan\n")
            assertTrue(plan, plan.contains("index_entries_date"))
            assertFalse(plan, plan.contains("USE TEMP B-TREE"))
        }
    }
}
