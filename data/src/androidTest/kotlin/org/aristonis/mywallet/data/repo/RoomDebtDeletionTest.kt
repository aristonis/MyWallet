package org.aristonis.mywallet.data.repo

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aristonis.mywallet.data.db.CategoryEntity
import org.aristonis.mywallet.data.db.EntryEntity
import org.aristonis.mywallet.data.db.EntryLegEntity
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.data.repo.RoomDeductionItemRepository
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.DebtRepository
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.usecase.DeleteDebt
import org.aristonis.mywallet.domain.usecase.PreviewDebtDeletion
import org.aristonis.mywallet.domain.usecase.RestoreEntries
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class RoomDebtDeletionTest {
    private lateinit var db: WalletDatabase
    private lateinit var entries: RoomEntryRepository
    private lateinit var debts: RoomDebtRepository
    private lateinit var ledger: RoomLedgerQueries
    private lateinit var runner: RoomTransactionRunner
    private val executed = CopyOnWriteArrayList<Pair<String, List<Any?>>>()
    private val day = LocalDate.of(2026, 10, 11)

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WalletDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback(RoomDatabase.QueryCallback { sql, args -> executed.add(sql to args) }, Executor { it.run() })
            .build()
        entries = RoomEntryRepository(db, db.entryDao())
        debts = RoomDebtRepository(db)
        ledger = RoomLedgerQueries(db.ledgerQueryDao())
        runner = RoomTransactionRunner(db)
        db.categoryDao().insertAll(listOf(CategoryEntity(id = 6, name = "Food", kind = "EXPENSE")))
    }
    @After fun tearDown() = db.close()

    private suspend fun debt(name: String = "Ali", side: DebtSide = DebtSide.I_OWE): Debt {
        val row = debts.getOrCreate(name, side, "USD")
        debts.updateDetails(row.id, day.plusDays(7), "rent")
        return debts.findById(row.id)!!
    }
    private fun opening(debt: Debt, amount: String = "10", date: LocalDate = day) =
        Transaction.OwedBefore(debtId = debt.id, side = debt.side, amount = Money.of(amount, "USD"), date = date)
    private suspend fun rawIncome(debt: Debt, amount: String = "3"): Long {
        val id = db.entryDao().insertEntry(EntryEntity(kind = "INCOME", date = day, note = "future deduction income"))
        db.entryDao().insertLegs(listOf(EntryLegEntity(entryId = id, role = "DEDUCTION_DEBT", position = 0,
            debtId = debt.id, amount = amount, currencyCode = "USD")))
        return id
    }
    private fun delete(port: EntryRepository = entries, debtPort: DebtRepository = debts) = DeleteDebt(runner, debtPort, port, ledger)
    private fun restore() = RestoreEntries(runner, entries, RoomCategoryRepository(db.categoryDao()),
        RoomAccountRepository(db.accountDao()), debts, ledger, RoomDeductionItemRepository(db))
    private class DeleteFault : RuntimeException("injected deletion fault")

    @Test fun rawIncomeIsExcludedBeforeDecodingAndItsHeaderAndDebtLegSurviveDeletion() = runBlocking {
        val chosen = debt()
        val own = entries.insert(opening(chosen))
        val retained = rawIncome(chosen)
        val before = db.entryDao().findById(retained)
        val preview = withTimeout(WAIT_MS) { PreviewDebtDeletion(runner, debts, entries, ledger)(chosen.id) }
        assertEquals(listOf(own), preview.entries.map { it.id })
        assertEquals(listOf(retained), preview.retainedEntryIds)
        assertEquals(Money.of("3", "USD"), preview.remainingAfter)
        val undo = withTimeout(WAIT_MS) { delete()(chosen.id) }
        assertEquals(listOf(own), undo.entries.map { it.id })
        assertEquals(before, db.entryDao().findById(retained))
        assertEquals(listOf(retained), entries.entryIdsOnDebt(chosen.id, emptySet()))
        assertEquals(chosen.copy(dueDate = null, note = null), debts.findById(chosen.id))
    }

    @Test fun unrelatedUnreadableHeadersAndDebtRowsSurviveAndDoNotBlockDelete() = runBlocking {
        val chosen = debt()
        val unrelated = debt("Omar")
        entries.insert(opening(chosen))
        val bad = db.entryDao().insertEntry(EntryEntity(kind = "UNKNOWN", date = day))
        db.entryDao().insertLegs(listOf(EntryLegEntity(entryId = bad, role = "DEBT", position = 0,
            debtId = unrelated.id, amount = "broken", currencyCode = "USD")))
        db.openHelper.writableDatabase.execSQL("UPDATE debts SET counterparty = '' WHERE id = ?", arrayOf(unrelated.id))
        val badBefore = db.entryDao().findById(bad)
        withTimeout(WAIT_MS) { delete()(chosen.id) }
        assertEquals(badBefore, db.entryDao().findById(bad))
        assertEquals(listOf(bad), entries.entryIdsOnDebt(unrelated.id, emptySet()))
        db.openHelper.writableDatabase.query("SELECT counterparty FROM debts WHERE id = ?", arrayOf(unrelated.id)).use {
            assertTrue(it.moveToFirst())
            assertEquals("", it.getString(0))
        }
    }

    @Test fun wholeDebtBatchAndUndoKeepOriginalRatesIdsAndDetailsOnBothSides() = runBlocking {
        for (side in DebtSide.entries) {
            val chosen = debt("Person $side", side)
            val principal = opening(chosen)
            val principalId = entries.insert(principal)
            val categoryId = if (side == DebtSide.I_OWE) {
                db.categoryDao().insertAll(listOf(CategoryEntity(id = 7, name = "Forgiven", kind = "INCOME")))
                7L
            } else 6L
            val writeOff = Transaction.WriteOff(side = side, debtId = chosen.id, amount = Money.of("4", "USD"),
                categoryId = categoryId, rateToBase = BigDecimal("1.12345678"), date = day, note = "keep this rate")
            val writtenId = entries.insert(writeOff)
            val undo = withTimeout(WAIT_MS) { delete()(chosen.id) }
            assertTrue(entries.entryIdsOnDebt(chosen.id, emptySet()).isEmpty())
            withTimeout(WAIT_MS) { restore()(undo) }
            assertEquals(principal.copy(id = principalId), entries.findById(principalId))
            assertEquals(writeOff.copy(id = writtenId), entries.findById(writtenId))
            assertEquals(chosen, debts.findById(chosen.id))
        }
    }

    @Test fun currentDeleteIncludesAnEntryAddedAfterPreview() = runBlocking {
        val chosen = debt()
        entries.insert(opening(chosen))
        PreviewDebtDeletion(runner, debts, entries, ledger)(chosen.id)
        val late = entries.insert(opening(chosen, "2"))
        val undo = withTimeout(WAIT_MS) { delete()(chosen.id) }
        assertTrue(undo.entries.any { it.id == late })
        assertTrue(entries.entryIdsOnDebt(chosen.id, emptySet()).isEmpty())
    }

    @Test fun lateDeleteFaultRollsBackEarlierDeletesInTheRealRoomTransaction() = runBlocking {
        val chosen = debt()
        val old = entries.insert(opening(chosen, date = day.minusDays(1)))
        val newer = entries.insert(opening(chosen))
        val before = listOf(entries.findById(old), entries.findById(newer))
        val failing = object : EntryRepository by entries {
            override suspend fun delete(id: Long) {
                entries.delete(id)
                if (id == old) throw DeleteFault()
            }
        }
        try {
            withTimeout(WAIT_MS) { delete(failing)(chosen.id) }
            fail("expected deletion fault")
        } catch (_: DeleteFault) {
            assertEquals(before, listOf(entries.findById(old), entries.findById(newer)))
            assertEquals(chosen, debts.findById(chosen.id))
        }
    }

    @Test fun faultAfterMetadataUpdateRollsBackEntriesAndDetailsInRoom() = runBlocking {
        val chosen = debt()
        val old = entries.insert(opening(chosen))
        val before = entries.findById(old)
        val failing = object : DebtRepository by debts {
            override suspend fun updateDetails(id: Long, dueDate: LocalDate?, note: String?) {
                debts.updateDetails(id, dueDate, note)
                throw DeleteFault()
            }
        }
        try {
            withTimeout(WAIT_MS) { delete(debtPort = failing)(chosen.id) }
            fail("expected metadata fault")
        } catch (_: DeleteFault) {
            assertEquals(before, entries.findById(old))
            assertEquals(chosen, debts.findById(chosen.id))
        }
    }

    @Test fun finalGuardFailureRollsBackTheDeletedRowsAndTheirDebtDetails() = runBlocking {
        val chosen = debt()
        val old = entries.insert(opening(chosen))
        val before = entries.findById(old)
        val retained = rawIncome(chosen, "-3")
        try {
            withTimeout(WAIT_MS) { delete()(chosen.id) }
            fail("expected negative remaining")
        } catch (failure: WalletException.DebtBelowZero) {
            assertEquals("Ali", failure.counterparty)
            assertEquals(Money.of("3", "USD"), failure.amount)
            assertEquals(before, entries.findById(old))
            assertEquals(chosen, debts.findById(chosen.id))
            assertEquals(setOf(old, retained), entries.entryIdsOnDebt(chosen.id, emptySet()).toSet())
        }
    }

    @Test fun filteredIdsAreDistinctNewestFirstHandleEmptyExclusionsAndUseTheDebtIndex() = runBlocking {
        val chosen = debt()
        val other = debt("Omar")
        val older = entries.insert(opening(chosen, date = day.minusDays(1)))
        val newer = entries.insert(opening(chosen))
        val retained = rawIncome(chosen)
        db.entryDao().insertLegs(listOf(EntryLegEntity(entryId = newer, role = "DEDUCTION_DEBT", position = 0,
            debtId = chosen.id, amount = "1", currencyCode = "USD")))
        entries.insert(opening(other))
        assertEquals(listOf(retained, newer, older), entries.entryIdsOnDebt(chosen.id, emptySet()))
        assertEquals(listOf(newer, older), entries.entryIdsOnDebt(chosen.id, setOf("INCOME")))
        assertEquals(emptyList<Long>(), entries.entryIdsOnDebt(chosen.id, setOf("INCOME", "OWED_BEFORE")))
        assertEquals(emptyList<Long>(), entries.entryIdsOnDebt(999, emptySet()))
        executed.clear()
        entries.entryIdsOnDebt(chosen.id, setOf("INCOME"))
        val reads = executed.filter { (sql, _) -> sql.trimStart().startsWith("SELECT", true) && sql.contains("entry_legs", true) }
        assertTrue("no debt-id query: $executed", reads.isNotEmpty())
        val plans = reads.map { (statement, args) ->
            db.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $statement", args.toTypedArray()).use { cursor ->
                buildString { while (cursor.moveToNext()) appendLine(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
            }
        }
        assertTrue("missing debt index: $plans", plans.any { it.contains("index_entry_legs_debtId_amount") })
        assertTrue("kind exclusion missing from SQL: $reads", reads.any { it.first.contains("kind", true) && it.second.contains("INCOME") })
    }

    private companion object { const val WAIT_MS = 5_000L }
}
