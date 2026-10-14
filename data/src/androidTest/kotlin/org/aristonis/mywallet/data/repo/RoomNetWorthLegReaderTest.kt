package org.aristonis.mywallet.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.data.db.NET_WORTH_ACCOUNT_LEGS_SQL
import org.aristonis.mywallet.data.db.NET_WORTH_DEBT_LEGS_SQL
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.DatedAccountLeg
import org.aristonis.mywallet.domain.port.DatedDebtLeg
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The dated legs a net worth series subtracts from today's balances, against the real database. The
 * read must stay on the date index with a hard row cap, so a long history costs only the window and
 * a wallet past the cap says so instead of building a partial line.
 */
@RunWith(AndroidJUnit4::class)
class RoomNetWorthLegReaderTest {

    private lateinit var db: WalletDatabase
    private lateinit var reader: RoomNetWorthLegReader

    private val day = LocalDate.of(2026, 7, 10)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WalletDatabase::class.java)
            .allowMainThreadQueries().build()
        reader = RoomNetWorthLegReader(db.ledgerQueryDao())
        sql.execSQL(
            "INSERT INTO accounts (id, name, typeKey, currencyCode, openingBalanceAmount, archived, sortOrder) " +
                "VALUES (1, 'Cash', 'cash', 'USD', '0', 0, 0)",
        )
        sql.execSQL("INSERT INTO debts (id, counterparty, counterpartyKey, side, currencyCode) VALUES (1, 'Sam', 'sam', 'I_OWE', 'EUR')")
    }

    @After
    fun tearDown() = db.close()

    private val sql get() = db.openHelper.writableDatabase

    private fun entry(id: Long, on: LocalDate, kind: String = "EXPENSE") {
        sql.execSQL("INSERT INTO entries (id, kind, date) VALUES (?, ?, ?)", arrayOf<Any?>(id, kind, on.toEpochDay()))
    }

    private fun accountLeg(entryId: Long, amount: String, position: Int = 0) {
        sql.execSQL(
            "INSERT INTO entry_legs (entryId, role, position, accountId, amount, currencyCode) VALUES (?, 'ACCOUNT', ?, 1, ?, 'USD')",
            arrayOf<Any?>(entryId, position, amount),
        )
    }

    private fun debtLeg(entryId: Long, amount: String) {
        sql.execSQL(
            "INSERT INTO entry_legs (entryId, role, position, debtId, amount, currencyCode) VALUES (?, 'DEBT', 1, 1, ?, 'EUR')",
            arrayOf<Any?>(entryId, amount),
        )
    }

    private fun categoryLeg(entryId: Long, amount: String) {
        sql.execSQL(
            "INSERT INTO entry_legs (entryId, role, position, categoryId, amount, currencyCode) VALUES (?, 'MAIN', 2, 3, ?, 'USD')",
            arrayOf<Any?>(entryId, amount),
        )
    }

    private fun plan(statement: String, vararg args: Any?): String =
        sql.query("EXPLAIN QUERY PLAN $statement", arrayOf(*args)).use { cursor ->
            buildString { while (cursor.moveToNext()) appendLine(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
        }

    @Test
    fun readsAccountAndDebtLegsOfEntriesDatedAfterTheDayWithTheirDates() = runTest {
        entry(1, day.minusDays(5)); accountLeg(1, "-1")
        entry(2, day); accountLeg(2, "-2")
        entry(3, day.plusDays(1)); accountLeg(3, "-3"); debtLeg(3, "4.50")
        entry(4, day.plusDays(30)); accountLeg(4, "5")

        val legs = reader.observeLegsAfter(day, 100).first()

        assertEquals(
            listOf(
                DatedAccountLeg(day.plusDays(1), 1, Money.of("-3", "USD")),
                DatedAccountLeg(day.plusDays(30), 1, Money.of("5", "USD")),
            ),
            legs.account.sortedBy { it.date },
        )
        assertEquals(listOf(DatedDebtLeg(day.plusDays(1), 1, Money.of("4.50", "EUR"))), legs.debt)
        assertFalse(legs.overLimit)
    }

    @Test
    fun leavesCategoryLegsOut() = runTest {
        entry(1, day.plusDays(1)); accountLeg(1, "-3"); categoryLeg(1, "3")

        val legs = reader.observeLegsAfter(day, 100).first()

        assertEquals(1, legs.account.size)
        assertTrue(legs.debt.isEmpty())
    }

    @Test
    fun exactlyTheLimitIsReadAndOneMoreIsOverTheLimitWithNoPartialLegs() = runTest {
        (1L..3L).forEach { entry(it, day.plusDays(it)); accountLeg(it, "-1") }
        entry(4, day.plusDays(4)); debtLeg(4, "1")

        val atLimit = reader.observeLegsAfter(day, 4).first()
        val over = reader.observeLegsAfter(day, 3).first()

        assertFalse(atLimit.overLimit)
        assertEquals(3, atLimit.account.size)
        assertEquals(1, atLimit.debt.size)
        assertTrue(over.overLimit)
        assertTrue(over.account.isEmpty() && over.debt.isEmpty())
    }

    @Test
    fun anAmountThatDoesNotParseFailsNamingItsEntry() = runTest {
        entry(1, day.plusDays(1)); accountLeg(1, "-3")
        entry(2, day.plusDays(2), kind = "INCOME"); accountLeg(2, "12,50")

        val failure = runCatching { reader.observeLegsAfter(day, 100).first() }.exceptionOrNull()

        assertTrue("expected UnreadableRow, got $failure", failure is WalletException.UnreadableRow)
        assertEquals(2L, (failure as WalletException.UnreadableRow).id)
        assertEquals("INCOME", failure.kind)
    }

    @Test
    fun emitsAgainWhenAnEntryIsAdded() = runTest {
        entry(1, day.plusDays(1)); accountLeg(1, "-3")
        val flow = reader.observeLegsAfter(day, 100)
        assertEquals(1, flow.first().account.size)

        entry(2, day.plusDays(2)); accountLeg(2, "-4")

        assertEquals(2, flow.first().account.size)
    }

    @Test
    fun bothReadsStartFromTheDateIndexAndSortNothing() {
        listOf(NET_WORTH_ACCOUNT_LEGS_SQL, NET_WORTH_DEBT_LEGS_SQL).forEach { statement ->
            val plan = plan(statement, 20454, 51)

            assertTrue("query plan was: $plan", plan.lineSequence().first().contains("index_entries_date"))
            assertFalse("query plan was: $plan", plan.contains("SCAN entry_legs"))
            assertFalse("query plan was: $plan", plan.contains("USE TEMP B-TREE"))
        }
    }
}
