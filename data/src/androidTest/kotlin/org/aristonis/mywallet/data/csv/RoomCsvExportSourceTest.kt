package org.aristonis.mywallet.data.csv

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aristonis.mywallet.data.db.AccountEntity
import org.aristonis.mywallet.data.db.CategoryEntity
import org.aristonis.mywallet.data.db.CurrencyEntity
import org.aristonis.mywallet.data.db.DebtEntity
import org.aristonis.mywallet.data.db.DeductionItemEntity
import org.aristonis.mywallet.data.db.EntryEntity
import org.aristonis.mywallet.data.db.SettingsEntity
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.data.repo.RoomCsvExportSource
import org.aristonis.mywallet.data.repo.RoomEntryRepository
import org.aristonis.mywallet.data.repo.RoomFxRepository
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.ExportTrackingCsv
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate

/** Exercises the one-shot export queries and metadata snapshot on a real Room database. */
@RunWith(AndroidJUnit4::class)
class RoomCsvExportSourceTest {
    private lateinit var db: WalletDatabase
    private lateinit var entries: RoomEntryRepository
    private lateinit var source: RoomCsvExportSource
    private val day = LocalDate.of(2026, 10, 12)

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), WalletDatabase::class.java,
        ).allowMainThreadQueries().build()
        db.currencyDao().insertAll(listOf(CurrencyEntity("USD", "$", 2), CurrencyEntity("EUR", "€", 2)))
        db.settingsDao().upsert(SettingsEntity(baseCurrencyCode = "USD", theme = "SYSTEM", schemaVersion = 1))
        db.accountDao().insertAll(listOf(AccountEntity(
            id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalanceAmount = "0",
        )))
        db.categoryDao().insertAll(listOf(
            CategoryEntity(id = 1, name = "Salary", kind = "INCOME"),
            CategoryEntity(id = 7, name = "Tax", kind = "EXPENSE"),
        ))
        entries = RoomEntryRepository(db, db.entryDao())
        source = RoomCsvExportSource(db, RoomFxRepository(db))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun inclusiveWindowAndAllTimeReadExactlyTheirCategoryLegs() = runBlocking {
        for ((id, date) in listOf(1L to day.minusDays(1), 2L to day, 3L to day.plusDays(1))) {
            entries.insert(Transaction.Expense(id, 1, Money.of("10", "USD"), 7, date = date))
        }
        val today = withTimeout(READ_TIMEOUT_MS) { source.read(DateRange(day, day)) }
        assertEquals(listOf(2L), today.legs.map { it.leg.entryId })
        val all = withTimeout(READ_TIMEOUT_MS) { source.read(DateRange.ALL_TIME) }
        assertEquals(setOf(1L, 2L, 3L), all.legs.map { it.leg.entryId }.toSet())
        assertEquals("USD", today.fx.baseCurrencyCode)
        assertEquals("Cash", today.accounts.single().name)
        assertEquals("Tax", today.categories.single().name)
    }

    @Test
    fun equalDeductionLegsRetainStoredPositionsAndOwnCounterparties() = runBlocking {
        db.debtDao().insertAll(listOf(
            DebtEntity(1, "Tax office", "tax office", "I_OWE", "USD"),
            DebtEntity(2, "Family", "family", "I_OWE", "USD"),
        ))
        db.deductionDao().insertItems(listOf("Tax office", "Family").mapIndexed { index, name ->
            DeductionItemEntity(id = index + 1L, name = name, nameKey = name.lowercase(java.util.Locale.ROOT),
                mode = "FIXED", value = "10", valueCurrencyCode = "USD", timing = "LATER",
                expenseCategoryId = 7, enabled = true, sortOrder = index)
        })
        val lines = listOf("Tax office", "Family").mapIndexed { index, name ->
            DeductionLine(index + 1L, index, name, DeductionMode.FIXED, BigDecimal.TEN, "USD",
                DeductionTiming.LATER, 7, Money.of("10", "USD"), index + 1L)
        }
        entries.insert(Transaction.Income(1, 1, Money.of("100", "USD"), 1, date = day,
            note = "receipt", deductions = lines))
        val snapshot = withTimeout(READ_TIMEOUT_MS) { source.read(DateRange(day, day)) }
        assertEquals(listOf(0, 1), snapshot.legs.filter { it.leg.role == LegRole.DEDUCTION }
            .map { it.position }.sorted())
        val csv = withTimeout(READ_TIMEOUT_MS) { ExportTrackingCsv(source, RfcCsvEncoder())(DateRange(day, day)) }
        assertTrue(csv.contains(",Tax office,receipt\r\n"))
        assertTrue(csv.contains(",Family,receipt\r\n"))
    }

    @Test
    fun aRefundOnAMissingCategoryIsPreservedAsNegativeExpense() = runBlocking {
        entries.insert(Transaction.Refund(1, 1, Money.of("5", "USD"), 99, date = day))
        val csv = withTimeout(READ_TIMEOUT_MS) { ExportTrackingCsv(source, RfcCsvEncoder())(DateRange(day, day)) }
        assertTrue(csv.contains(",REFUND,MAIN,99,,EXPENSE,-5,USD,1,-5,Cash,,"))
    }

    @Test
    fun missingRateAndUnknownKindFailWithoutWaitingForALiveFlow() = runBlocking {
        entries.insert(Transaction.Expense(1, 1, Money.of("1", "EUR"), 7, date = day))
        val missing = runCatching {
            withTimeout(READ_TIMEOUT_MS) { ExportTrackingCsv(source, RfcCsvEncoder())(DateRange(day, day)) }
        }.exceptionOrNull()
        assertTrue(missing is WalletException.MissingRate)
        db.entryDao().insertEntry(EntryEntity(2, "FUTURE_KIND", day))
        val unreadable = runCatching {
            withTimeout(READ_TIMEOUT_MS) { source.read(DateRange(day, day)) }
        }.exceptionOrNull()
        assertTrue(unreadable is WalletException.UnreadableRow)
    }

    @Test
    fun anUnreadableEntryOutsideTheWindowRefusesTheEntireExportSnapshot() = runBlocking {
        entries.insert(Transaction.Expense(1, 1, Money.of("10", "USD"), 7, date = day))
        db.entryDao().insertEntry(EntryEntity(2, "FUTURE_KIND", day.minusDays(1)))
        val failure = runCatching {
            withTimeout(READ_TIMEOUT_MS) { source.read(DateRange(day, day)) }
        }.exceptionOrNull()
        assertTrue(failure is WalletException.UnreadableRow)
    }

    private companion object {
        const val READ_TIMEOUT_MS = 5_000L
    }
}
