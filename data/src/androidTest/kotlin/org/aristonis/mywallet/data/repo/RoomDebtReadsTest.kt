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
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class RoomDebtReadsTest {
    private lateinit var db: WalletDatabase
    private lateinit var entries: RoomEntryRepository
    private lateinit var ledger: RoomLedgerQueries
    private lateinit var debts: RoomDebtRepository
    private val executed = CopyOnWriteArrayList<Pair<String, List<Any?>>>()
    private val day = LocalDate.of(2026, 10, 10)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WalletDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback(RoomDatabase.QueryCallback { sql, args -> executed.add(sql to args) }, Executor { it.run() })
            .build()
        entries = RoomEntryRepository(db, db.entryDao())
        ledger = RoomLedgerQueries(db.ledgerQueryDao())
        debts = RoomDebtRepository(db)
    }
    @After fun tearDown() = db.close()

    private suspend fun debt(name: String) = debts.getOrCreate(name, DebtSide.I_OWE, "USD")
    private fun opening(id: Long, amount: String, date: LocalDate = day) =
        Transaction.OwedBefore(debtId = id, side = DebtSide.I_OWE, amount = Money.of(amount, "USD"), date = date)

    @Test fun debtHistoryReturnsWholeMultiLegEntriesNewestFirstAndExcludesOtherDebts() = runTest {
        val chosen = debt("Ali")
        val other = debt("Omar")
        val oldId = entries.insert(opening(chosen.id, "12", day.minusDays(1)))
        entries.insert(opening(other.id, "99"))
        val borrowed = Transaction.Borrow(accountId = 1, debtId = chosen.id, amount = Money.of("5", "USD"), date = day)
        val newId = entries.insert(borrowed)
        val tiedId = entries.insert(opening(chosen.id, "1"))
        val result = entries.observeOnDebt(chosen.id).first()
        assertEquals(listOf(tiedId, newId, oldId), result.map { it.id })
        assertEquals(borrowed.copy(id = newId), result[1])
    }

    @Test fun unreadableUnrelatedRowsAreNotReadByEitherDebtQuery() = runTest {
        val chosen = debt("Ali")
        val other = debt("Omar")
        val wanted = entries.insert(opening(chosen.id, "12"))
        val bad = entries.insert(opening(other.id, "3"))
        db.openHelper.writableDatabase.execSQL("UPDATE entry_legs SET amount = 'broken' WHERE entryId = ?", arrayOf(bad))
        assertEquals(listOf(wanted), entries.observeOnDebt(chosen.id).first().map { it.id })
        assertEquals(listOf(Money.of("12", "USD")), ledger.debtLegs(setOf(chosen.id)).map { it.amount })
    }

    @Test fun malformedRequestedDebtLegFailsWithTheOwningEntry() = runTest {
        val chosen = debt("Ali")
        val bad = entries.insert(opening(chosen.id, "3"))
        db.openHelper.writableDatabase.execSQL("UPDATE entry_legs SET amount = '12,50' WHERE entryId = ?", arrayOf(bad))
        try {
            ledger.debtLegs(setOf(chosen.id))
            fail("expected UnreadableRow")
        } catch (failure: WalletException.UnreadableRow) {
            assertEquals(bad, failure.id)
            assertEquals("OWED_BEFORE", failure.kind)
        }
    }

    @Test fun guardQueryReadsOnlyTheRequestedDebtIdsAndKeepsEachExactAmount() = runTest {
        val first = debt("Ali")
        val second = debt("Omar")
        val unrelated = debt("Zaid")
        entries.insert(opening(first.id, "1000000000000000.01"))
        entries.insert(opening(second.id, "0.02"))
        entries.insert(opening(unrelated.id, "99"))
        val legs = ledger.debtLegs(setOf(first.id, second.id))
        assertEquals(setOf(first.id, second.id), legs.map { it.debtId }.toSet())
        assertEquals(Money.of("1000000000000000.01", "USD"), legs.first { it.debtId == first.id }.amount)
        assertEquals(Money.of("0.02", "USD"), legs.first { it.debtId == second.id }.amount)
    }

    @Test fun anUnknownRequestedDebtSideFailsWithItsEntryId() = runTest {
        val chosen = debt("Ali")
        val bad = entries.insert(opening(chosen.id, "3"))
        db.openHelper.writableDatabase.execSQL("UPDATE debts SET side = 'UNKNOWN' WHERE id = ?", arrayOf(chosen.id))
        try {
            ledger.debtLegs(setOf(chosen.id))
            fail("expected UnreadableRow")
        } catch (failure: WalletException.UnreadableRow) {
            assertEquals(bad, failure.id)
        }
    }

    @Test fun emptyGuardQueryReturnsNoLegsWithoutReadingAnyDebt() = runTest {
        val other = debt("Ali")
        val bad = entries.insert(opening(other.id, "3"))
        db.openHelper.writableDatabase.execSQL("UPDATE entry_legs SET amount = 'broken' WHERE entryId = ?", arrayOf(bad))
        assertTrue(ledger.debtLegs(emptySet()).isEmpty())
        assertTrue(entries.observeOnDebt(999).first().isEmpty())
    }

    @Test fun historyReEmitsAfterAnEntryIsInserted() = runBlocking {
        val chosen = debt("Ali")
        entries.insert(opening(chosen.id, "3"))
        val changed = async(Dispatchers.Default) {
            withTimeout(WAIT_MS) { entries.observeOnDebt(chosen.id).first { it.size == 2 } }
        }
        entries.insert(opening(chosen.id, "4"))
        assertEquals(2, changed.await().size)
    }

    @Test fun debtHistoryAndGuardReadsUseTheDebtIndexInsteadOfScanningHistory() = runTest {
        val chosen = debt("Ali")
        entries.insert(opening(chosen.id, "3"))
        executed.clear()
        entries.observeOnDebt(chosen.id).first()
        ledger.debtLegs(setOf(chosen.id))
        val reads = executed.filter { (statement, _) ->
            statement.trimStart().startsWith("SELECT", true) && statement.contains("entry_legs", true) && statement.contains("debtId", true)
        }
        assertTrue("no filtered debt read: $executed", reads.isNotEmpty())
        val plans = reads.map { (statement, args) ->
            db.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $statement", args.toTypedArray()).use { cursor ->
                buildString { while (cursor.moveToNext()) appendLine(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
            }
        }
        assertTrue("debt index absent: $plans", plans.any { it.contains("index_entry_legs_debtId_amount") })
        assertTrue("whole history was loaded: $reads", reads.all { (statement, _) ->
            statement.contains("WHERE", true) && statement.contains("debtId", true)
        })
    }

    private companion object { const val WAIT_MS = 5_000L }
}
