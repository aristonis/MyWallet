package org.aristonis.mywallet.data.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aristonis.mywallet.data.db.AccountEntity
import org.aristonis.mywallet.data.db.CategoryEntity
import org.aristonis.mywallet.data.db.CurrencyEntity
import org.aristonis.mywallet.data.db.SettingsEntity
import org.aristonis.mywallet.data.db.toEntity
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.data.repo.RoomAccountRepository
import org.aristonis.mywallet.data.repo.RoomCategoryRepository
import org.aristonis.mywallet.data.repo.RoomCategoryRoleRepository
import org.aristonis.mywallet.data.repo.RoomCurrencyRepository
import org.aristonis.mywallet.data.repo.RoomDebtRepository
import org.aristonis.mywallet.data.repo.RoomDeductionItemRepository
import org.aristonis.mywallet.data.repo.RoomEntryRepository
import org.aristonis.mywallet.data.repo.RoomFxRepository
import org.aristonis.mywallet.data.repo.RoomLedgerQueries
import org.aristonis.mywallet.data.repo.RoomRateRepository
import org.aristonis.mywallet.data.repo.RoomSettingsRepository
import org.aristonis.mywallet.data.repo.RoomTransactionRunner
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.DeductionItem
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.service.RoleCategories
import org.aristonis.mywallet.domain.usecase.DeleteEntry
import org.aristonis.mywallet.domain.usecase.RestoreEntries
import org.aristonis.mywallet.domain.usecase.SaveEntry
import org.aristonis.mywallet.domain.usecase.SetExchangeRate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate

/** Exercises salary snapshots and save rollback through the same Room transaction used by the app. */
@RunWith(AndroidJUnit4::class)
class SalaryEntryOnRoomTest {
    private lateinit var db: WalletDatabase
    private lateinit var entries: RoomEntryRepository
    private lateinit var ledger: RoomLedgerQueries
    private lateinit var runner: RoomTransactionRunner
    private lateinit var deductions: RoomDeductionItemRepository
    private val day = LocalDate.of(2026, 10, 12)

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), WalletDatabase::class.java,
        ).allowMainThreadQueries().build()
        db.currencyDao().insertAll(listOf(CurrencyEntity("USD", "$", 2), CurrencyEntity("SYP", "SYP", 2)))
        db.settingsDao().upsert(SettingsEntity(
            baseCurrencyCode = "USD", theme = "SYSTEM", schemaVersion = 1, saveTransactionRates = true,
        ))
        db.accountDao().insertAll(listOf(
            AccountEntity(id = 1, name = "Bank", typeKey = "cash", currencyCode = "USD", openingBalanceAmount = "0"),
            AccountEntity(id = 2, name = "Wallet", typeKey = "cash", currencyCode = "SYP", openingBalanceAmount = "0"),
        ))
        db.categoryDao().insertAll(listOf(
            CategoryEntity(id = 5, name = "Salary", kind = "INCOME"),
            CategoryEntity(id = 6, name = "Tax", kind = "EXPENSE"),
            CategoryEntity(id = 7, name = "Bills", kind = "EXPENSE"),
        ))
        entries = RoomEntryRepository(db, db.entryDao())
        ledger = RoomLedgerQueries(db.ledgerQueryDao())
        runner = RoomTransactionRunner(db)
        deductions = RoomDeductionItemRepository(db)
        db.deductionDao().insertItems(listOf(
            item(10, "Tax", "10", DeductionTiming.LATER, 6).toEntity(),
            item(11, "Bills", "2", DeductionTiming.NOW, 7).toEntity(),
        ))
    }

    @After fun tearDown() = db.close()

    @Test fun unresolvedSalaryDeductionsSaveAndUndoThroughRoomWithExactSnapshotsAndDebts() = runBlocking {
        val id = withTimeout(RETURN_WITHIN_MS) { save()(salary(), emptyMap()).id }
        val original = entries.findById(id) as Transaction.Income
        assertEquals(money("1000"), original.amount)
        assertEquals(money("980"), original.toParts().legs.single { it.role == LegRole.ACCOUNT }.amount)
        assertEquals(listOf("Tax", "Bills"), original.deductions.map { it.name })
        assertEquals(listOf(money("100"), money("20")), original.deductions.map { it.amount })
        val taxDebt = requireNotNull(original.deductions.first().debtId)
        assertNull(original.deductions.last().debtId)
        assertEquals("Tax", RoomDebtRepository(db).findById(taxDebt)!!.counterparty)
        assertEquals(money("100"), ledger.debtLegs(setOf(taxDebt)).single().amount)
        assertEquals(2, db.deductionDao().getAllLines().size)
        val debts = db.debtDao().getAll()
        val undo = DeleteEntry(runner, entries, RoomDebtRepository(db), ledger)(id)
        assertTrue(db.deductionDao().getAllLines().isEmpty())
        assertTrue(db.entryDao().getAll().isEmpty())
        assertTrue(ledger.debtLegs(setOf(taxDebt)).isEmpty())
        withTimeout(RETURN_WITHIN_MS) {
            RestoreEntries(runner, entries, RoomCategoryRepository(db.categoryDao()),
                RoomAccountRepository(db.accountDao()), RoomDebtRepository(db), ledger, deductions)(undo)
        }
        assertEquals(original, entries.findById(id))
        assertEquals(debts, db.debtDao().getAll())
        assertEquals(2, db.deductionDao().getAllLines().size)
    }

    @Test fun aRoundedZeroPaidNowDeductionKeepsItsSnapshotAndZeroLegThroughWriteReadDeleteUndo() = runBlocking {
        roundedZeroRoundTrip(DeductionTiming.NOW)
    }

    @Test fun aRoundedZeroOwedLaterDeductionKeepsItsSnapshotAndZeroLegButNoDebtThroughRoom() = runBlocking {
        roundedZeroRoundTrip(DeductionTiming.LATER)
    }

    /**
     * 10% of 0.01 USD rounds to 0.00. The line and its zero category leg are stored so the chosen rule
     * survives; owed later, nothing is owed, so there is no debt id, debt leg or debt row.
     */
    private suspend fun roundedZeroRoundTrip(timing: DeductionTiming) {
        val entry = Transaction.Income(accountId = 1, amount = money("0.01"), categoryId = 5, date = day,
            deductions = listOf(line(10, 0, "Tax", "10", "0.00", timing, 6)))
        val id = withTimeout(RETURN_WITHIN_MS) { save()(entry, emptyMap()).id }

        val original = entries.findById(id) as Transaction.Income
        val stored = original.deductions.single()
        assertEquals("Tax", stored.name)
        assertEquals(timing, stored.timing)
        assertEquals(6L, stored.categoryId)
        assertEquals(money("0.00"), stored.amount)
        assertNull(stored.debtId)
        assertRoundedZeroRows(id)
        assertEquals(money("0.01"), original.toParts().legs.single { it.role == LegRole.ACCOUNT }.amount)

        val undo = DeleteEntry(runner, entries, RoomDebtRepository(db), ledger)(id)
        assertTrue(db.entryDao().getAll().isEmpty())
        assertTrue(db.deductionDao().getAllLines().isEmpty())
        withTimeout(RETURN_WITHIN_MS) {
            RestoreEntries(runner, entries, RoomCategoryRepository(db.categoryDao()),
                RoomAccountRepository(db.accountDao()), RoomDebtRepository(db), ledger, deductions)(undo)
        }
        assertEquals(original, entries.findById(id))
        assertRoundedZeroRows(id)
    }

    private suspend fun assertRoundedZeroRows(id: Long) {
        val legs = db.entryDao().findById(id)
        val deduction = legs.single { it.role == LegRole.DEDUCTION.name }
        assertEquals(6L, deduction.categoryId)
        assertEquals(0, BigDecimal(requireNotNull(deduction.amount)).signum())
        assertTrue(legs.none { it.role == LegRole.DEDUCTION_DEBT.name })
        assertEquals(1, db.deductionDao().getAllLines().size)
        assertTrue(db.debtDao().getAll().isEmpty())
    }

    @Test fun aFaultAfterTheRealSalaryInsertRollsBackItsLinesAndNewDebt() = runBlocking {
        val failingEntries = object : EntryRepository by entries {
            override suspend fun insert(entry: Transaction): Long {
                entries.insert(entry)
                throw SaveFault()
            }
        }
        val failure = runCatching {
            withTimeout(RETURN_WITHIN_MS) { save(entryPort = failingEntries)(salary(), emptyMap()) }
        }.exceptionOrNull()
        assertTrue("was $failure", failure is SaveFault)
        assertNoSalaryWrites()
        assertEquals(2, db.deductionDao().getAllItems().size)
    }

    @Test fun aFaultAfterTypedRateFillRollsBackSalaryDebtSnapshotsRateAndOlderLegFill() = runBlocking {
        val older = Transaction.Expense(accountId = 2, amount = money("5000", "SYP"), categoryId = 7, date = day)
        val oldId = entries.insert(older)
        val before = entries.findById(oldId)
        val failure = runCatching {
            withTimeout(RETURN_WITHIN_MS) {
                save(ledgerPort = failingRateFill())(salary(), mapOf("SYP" to BigDecimal("0.0002")))
            }
        }.exceptionOrNull()
        assertTrue("was $failure", failure is SaveFault)
        assertEquals(before, entries.findById(oldId))
        assertEquals(1, db.entryDao().getAll().size)
        assertTrue(db.deductionDao().getAllLines().isEmpty())
        assertTrue(db.debtDao().getAll().isEmpty())
        assertNull(RoomRateRepository(db.rateDao()).findByCode("SYP"))
    }

    @Test fun aFeeSaveFaultAfterRateFillRollsBackTheFallbackBucketEntryAndRate() = runBlocking {
        val categories = db.categoryDao().getAll()
        val expense = Transaction.Expense(accountId = 2, amount = money("50", "SYP"),
            fee = money("1", "SYP"), categoryId = 7, date = day)
        val failure = runCatching {
            withTimeout(RETURN_WITHIN_MS) {
                save(ledgerPort = failingRateFill())(expense, mapOf("SYP" to BigDecimal("0.0002")))
            }
        }.exceptionOrNull()
        assertTrue("was $failure", failure is SaveFault)
        assertNoSalaryWrites()
        assertEquals(categories, db.categoryDao().getAll())
        assertNull(RoomRateRepository(db.rateDao()).findByCode("SYP"))
    }

    private suspend fun assertNoSalaryWrites() {
        assertTrue(db.entryDao().getAll().isEmpty())
        assertTrue(db.deductionDao().getAllLines().isEmpty())
        assertTrue(db.debtDao().getAll().isEmpty())
    }

    private fun failingRateFill(): LedgerQueries = object : LedgerQueries by ledger {
        override suspend fun fillMissingRate(code: String, rate: BigDecimal): Int {
            ledger.fillMissingRate(code, rate)
            throw SaveFault()
        }
    }

    private fun save(entryPort: EntryRepository = entries, ledgerPort: LedgerQueries = ledger): SaveEntry {
        val categories = RoomCategoryRepository(db.categoryDao())
        val settings = RoomSettingsRepository(db.settingsDao())
        val rates = RoomRateRepository(db.rateDao())
        return SaveEntry(runner, RoomAccountRepository(db.accountDao()), categories, entryPort,
            RoomFxRepository(db), settings,
            SetExchangeRate(RoomCurrencyRepository(db.currencyDao()), rates, ledgerPort, runner, settings),
            RoomDebtRepository(db), ledgerPort,
            RoleCategories(RoomCategoryRoleRepository(db.categoryRoleDao()), categories), deductions)
    }

    private fun salary() = Transaction.Income(accountId = 1, amount = money("1000"), categoryId = 5,
        date = day, deductions = listOf(
            line(10, 0, "Tax", "10", "100", DeductionTiming.LATER, 6),
            line(11, 1, "Bills", "2", "20", DeductionTiming.NOW, 7),
        ))

    private fun line(id: Long, position: Int, name: String, value: String, amount: String,
        timing: DeductionTiming, category: Long) = DeductionLine(
        itemId = id, position = position, name = name, mode = DeductionMode.PERCENT,
        value = BigDecimal(value), valueCurrencyCode = null, timing = timing, categoryId = category,
        amount = money(amount),
    )

    private fun item(id: Long, name: String, value: String, timing: DeductionTiming, category: Long) =
        DeductionItem(id = id, name = name, mode = DeductionMode.PERCENT, value = BigDecimal(value),
            timing = timing, expenseCategoryId = category)

    private fun money(amount: String, code: String = "USD") = Money.of(amount, code)
    private class SaveFault : RuntimeException()
    private companion object { const val RETURN_WITHIN_MS = 10_000L }
}
