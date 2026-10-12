package org.aristonis.mywallet.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.aristonis.mywallet.data.db.CategoryEntity
import org.aristonis.mywallet.data.db.DebtEntity
import org.aristonis.mywallet.data.db.DeductionItemEntity
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.data.db.scalar
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.DeductionItem
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The deduction lines of an income live in their own table beside its legs. These run against a real
 * in-memory schema because the contract is what the rows look like after a replace or delete, and that
 * a range read hands every entry its own lines.
 */
@RunWith(AndroidJUnit4::class)
class RoomIncomeDeductionLinesTest {

    private lateinit var db: WalletDatabase
    private lateinit var repository: RoomEntryRepository

    private val day = LocalDate.of(2026, 10, 5)
    private val range = DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
    private val sql get() = db.openHelper.writableDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WalletDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomEntryRepository(db, db.entryDao())
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
                    itemRow(ITEM_TAX, "Tax", "tax", TAX),
                    itemRow(ITEM_FAMILY, "Family", "family", FAMILY),
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

    private fun itemRow(id: Long, name: String, key: String, categoryId: Long) = DeductionItemEntity(
        id = id, name = name, nameKey = key, mode = "FIXED", value = "10", valueCurrencyCode = "USD",
        timing = "NOW", expenseCategoryId = categoryId, enabled = true, sortOrder = 0,
    )

    private fun nowLine(position: Int = 0, amount: String = "100") = DeductionLine(
        itemId = ITEM_TAX, position = position, name = "Tax", mode = DeductionMode.FIXED,
        value = BigDecimal(amount), valueCurrencyCode = "USD", timing = DeductionTiming.NOW,
        categoryId = TAX, amount = Money.of(amount, "USD"),
    )

    private fun laterLine(position: Int = 1, amount: String = "50") = DeductionLine(
        itemId = ITEM_FAMILY, position = position, name = "Family", mode = DeductionMode.FIXED,
        value = BigDecimal(amount), valueCurrencyCode = "USD", timing = DeductionTiming.LATER,
        categoryId = FAMILY, amount = Money.of(amount, "USD"), debtId = DEBT,
    )

    private fun income(
        id: Long = 0,
        date: LocalDate = day,
        deductions: List<DeductionLine> = listOf(nowLine(), laterLine()),
    ) = Transaction.Income(
        id = id, accountId = 1, amount = Money.of("1000", "USD"), categoryId = SALARY, date = date,
        rateToBase = BigDecimal.ONE, deductions = deductions,
    )

    private fun lineCount(entryId: Long) =
        sql.scalar("SELECT COUNT(*) FROM income_deduction_lines WHERE entryId = ?", entryId)

    private fun legCount(entryId: Long) = sql.scalar("SELECT COUNT(*) FROM entry_legs WHERE entryId = ?", entryId)

    @Test
    fun replacingAnIncomeWithFewerLinesLeavesExactlyTheNewLinesAndTheirLegs() = runTest {
        val id = repository.insert(income())
        assertEquals(income(id), repository.findById(id))
        assertEquals("2", lineCount(id))
        assertEquals("5", legCount(id))

        repository.replace(income(id, deductions = listOf(nowLine())))

        assertEquals(income(id, deductions = listOf(nowLine())), repository.findById(id))
        assertEquals("1", lineCount(id))
        assertEquals("3", legCount(id))

        repository.replace(income(id, deductions = emptyList()))

        assertEquals(income(id, deductions = emptyList()), repository.findById(id))
        assertEquals("0", lineCount(id))
        assertEquals("2", legCount(id))
    }

    @Test
    fun findByIdReadsTheIncomeWithItsLinesInPositionOrder() = runTest {
        val id = repository.insert(income(deductions = listOf(nowLine(0, "20"), laterLine(1, "30"), nowLine(2, "40"))))

        val read = repository.findById(id) as Transaction.Income

        assertEquals(listOf(0, 1, 2), read.deductions.map { it.position })
        assertEquals(listOf("20", "30", "40"), read.deductions.map { it.amount.amount.toPlainString() })
        assertEquals(DEBT, read.deductions[1].debtId)
    }

    @Test
    fun deletingAnIncomeRemovesItsLinesAndLegsButKeepsTheRules() = runTest {
        val id = repository.insert(income())

        repository.delete(id)

        assertNull(repository.findById(id))
        assertEquals("0", lineCount(id))
        assertEquals("0", legCount(id))
        assertEquals("2", sql.scalar("SELECT COUNT(*) FROM deduction_items"))
        assertEquals(false, RoomDeductionItemRepository(db).isInUse(ITEM_TAX))
    }

    @Test
    fun aRangeReadGivesEachEntryItsOwnLinesAndSkipsEntriesOutsideTheRange() = runTest {
        val older = repository.insert(income(date = day.minusDays(2), deductions = listOf(nowLine(amount = "20"))))
        val newer = repository.insert(income(date = day))
        val plain = repository.insert(income(date = day.minusDays(1), deductions = emptyList()))
        repository.insert(income(date = range.endInclusive.plusDays(1)))

        val read = repository.observeBetween(range).first()

        assertEquals(listOf(newer, plain, older), read.map { it.id })
        assertEquals(income(newer), read[0])
        assertEquals(income(plain, day.minusDays(1), emptyList()), read[1])
        assertEquals(income(older, day.minusDays(2), listOf(nowLine(amount = "20"))), read[2])
    }

    @Test
    fun aPageReadGivesEachEntryItsOwnLines() = runTest {
        val first = repository.insert(income(date = day, deductions = listOf(nowLine(amount = "20"))))
        val second = repository.insert(income(date = day.minusDays(1)))

        val page = repository.observePage(range, null, 10).first()

        assertEquals(listOf(first, second), page.map { it.id })
        assertEquals(income(first, day, listOf(nowLine(amount = "20"))), page[0])
        assertEquals(income(second, day.minusDays(1)), page[1])
    }

    @Test
    fun aRangeReadEmitsAgainWhenOnlyTheLinesTableChanges() = runBlocking {
        val id = repository.insert(income())
        val seen = Channel<List<String>>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) {
            repository.observeBetween(range).collect { seen.send(it.single().lineNames()) }
        }
        try {
            assertEquals(listOf("Tax", "Family"), withTimeout(WAIT_MS) { seen.receive() })

            // Inside a Room transaction, as the app's own writes are, so the invalidation tracker notices it.
            db.runInTransaction {
                sql.execSQL(
                    "UPDATE income_deduction_lines SET name = 'Levy' WHERE entryId = ? AND position = 0",
                    arrayOf(id),
                )
            }

            assertEquals(listOf("Levy", "Family"), withTimeout(WAIT_MS) { seen.receive() })
        } finally {
            collector.cancelAndJoin()
        }
    }

    private fun Transaction.lineNames(): List<String> = (this as Transaction.Income).deductions.map { it.name }

    private companion object {
        const val WAIT_MS = 5_000L
        const val SALARY = 5L
        const val TAX = 21L
        const val FAMILY = 22L
        const val ITEM_TAX = 1L
        const val ITEM_FAMILY = 2L
        const val DEBT = 31L
    }
}
