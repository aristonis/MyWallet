package org.aristonis.mywallet.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aristonis.mywallet.data.db.AccountEntity
import org.aristonis.mywallet.data.db.CategoryEntity
import org.aristonis.mywallet.data.db.CurrencyEntity
import org.aristonis.mywallet.data.db.RateEntity
import org.aristonis.mywallet.data.db.SettingsEntity
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.data.repo.RoomDeductionItemRepository
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.service.DebtRemaining
import org.aristonis.mywallet.domain.service.RoleCategories
import org.aristonis.mywallet.domain.usecase.RecordRepayment
import org.aristonis.mywallet.domain.usecase.SaveEntry
import org.aristonis.mywallet.domain.usecase.SetExchangeRate
import org.aristonis.mywallet.domain.usecase.WriteOffDebt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate

/** Exercises repayment and write-off with real nested Room transactions and one-shot guard reads. */
@RunWith(AndroidJUnit4::class)
class RoomRepaymentTest {
    private lateinit var db: WalletDatabase
    private lateinit var runner: RoomTransactionRunner
    private lateinit var entries: RoomEntryRepository
    private lateinit var debts: RoomDebtRepository
    private lateinit var ledger: RoomLedgerQueries
    private lateinit var accounts: RoomAccountRepository
    private lateinit var categories: RoomCategoryRepository
    private lateinit var rates: RoomRateRepository
    private lateinit var settings: RoomSettingsRepository
    private lateinit var fx: RoomFxRepository
    private val day = LocalDate.of(2026, 10, 11)

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WalletDatabase::class.java)
            .allowMainThreadQueries().build()
        seedCatalog()
        runner = RoomTransactionRunner(db)
        entries = RoomEntryRepository(db, db.entryDao())
        debts = RoomDebtRepository(db)
        ledger = RoomLedgerQueries(db.ledgerQueryDao())
        accounts = RoomAccountRepository(db.accountDao())
        categories = RoomCategoryRepository(db.categoryDao())
        rates = RoomRateRepository(db.rateDao())
        settings = RoomSettingsRepository(db.settingsDao())
        fx = RoomFxRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedCatalog() {
        db.currencyDao().insertAll(listOf(CurrencyEntity("USD", "$", 2), CurrencyEntity("SYP", "£S", 2)))
        db.rateDao().insertAll(listOf(RateEntity(currencyCode = "SYP", rateToBase = "0.0001")))
        db.settingsDao().upsert(SettingsEntity(baseCurrencyCode = "USD", theme = "SYSTEM", schemaVersion = 1,
            saveTransactionRates = true))
        db.accountDao().insertAll(listOf(
            AccountEntity(id = USD_ACCOUNT, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalanceAmount = "0"),
            AccountEntity(id = SYP_ACCOUNT, name = "Wallet", typeKey = "cash", currencyCode = "SYP", openingBalanceAmount = "0"),
        ))
        db.categoryDao().insertAll(listOf(
            CategoryEntity(id = EXPENSE_CATEGORY, name = "Loss", kind = "EXPENSE"),
            CategoryEntity(id = INCOME_CATEGORY, name = "Forgiveness", kind = "INCOME"),
        ))
    }

    private fun saveEntry(ratePort: RateRepository = rates): SaveEntry = SaveEntry(
        runner, accounts, categories, entries, fx, settings,
        SetExchangeRate(RoomCurrencyRepository(db.currencyDao()), ratePort, ledger, runner, settings),
        debts, ledger, RoleCategories(RoomCategoryRoleRepository(db.categoryRoleDao()), categories),
            RoomDeductionItemRepository(db),
    )

    private fun record(save: SaveEntry = saveEntry()) =
        RecordRepayment(runner, debts, ledger, entries, accounts, fx, save)

    private fun writeOff(save: SaveEntry = saveEntry()) = WriteOffDebt(runner, debts, ledger, save)

    private suspend fun loan(side: DebtSide, amount: String, code: String = "SYP"): Debt {
        val debt = debts.getOrCreate("Person $side", side, code)
        debts.updateDetails(debt.id, day.plusDays(7), "keep these details")
        entries.insert(Transaction.OwedBefore(debtId = debt.id, side = side,
            amount = Money.of(amount, code), date = day))
        return debts.findById(debt.id)!!
    }

    private suspend fun remaining(debt: Debt): Money =
        DebtRemaining.byDebt(ledger.debtLegs(setOf(debt.id)))[debt.id]?.remaining ?: Money.zero(debt.currencyCode)

    @Test
    fun fullCrossCurrencyPayoffKeepsExactPrincipalAndReturnsThroughNestedRoomSaveOnBothSides() = runBlocking {
        for (side in DebtSide.entries) {
            val debt = loan(side, "1000050")

            val saved = withTimeout(RETURN_WITHIN_MS) {
                record()(debt.id, USD_ACCOUNT, principal = null, date = day, pairRate = BigDecimal("13000"))
            }

            val payment = entries.findById(saved.id) as Transaction.Repayment
            assertEquals(side, payment.side)
            assertEquals(Money.of("1000050", "SYP"), payment.principal)
            assertEquals(Money.of("76.93", "USD"), payment.accountAmount)
            assertEquals(0, BigDecimal("13000").compareTo(payment.pairRate!!))
            assertEquals(Money.zero("SYP"), remaining(debt))
            val accountLeg = db.entryDao().getAllLegs().single { it.entryId == saved.id && it.accountId == USD_ACCOUNT }
            val expected = if (side == DebtSide.I_OWE) "-76.93" else "76.93"
            assertEquals(Money.of(expected, "USD"), Money.of(accountLeg.amount, accountLeg.currencyCode))
        }
    }

    @Test
    fun writeOffReadsTheExactCurrentRestAfterRepaymentAndUsesTheChosenCategoryOnBothSides() = runBlocking {
        for (side in DebtSide.entries) {
            val debt = loan(side, "1000050")
            withTimeout(RETURN_WITHIN_MS) {
                record()(debt.id, USD_ACCOUNT, Money.of("13000", "SYP"), day, pairRate = BigDecimal("13000"))
            }
            val category = if (side == DebtSide.I_OWE) INCOME_CATEGORY else EXPENSE_CATEGORY

            val saved = withTimeout(RETURN_WITHIN_MS) { writeOff()(debt.id, category, day, note = "settle the rest") }

            val forgiven = entries.findById(saved.id) as Transaction.WriteOff
            assertEquals(Money.of("987050", "SYP"), forgiven.amount)
            assertEquals(category, forgiven.categoryId)
            assertEquals(side, forgiven.side)
            assertEquals("settle the rest", forgiven.note)
            assertEquals(0, BigDecimal("0.0001").compareTo(forgiven.rateToBase!!))
            assertEquals(Money.zero("SYP"), remaining(debt))
            assertTrue(db.entryDao().getAllLegs().none { it.entryId == saved.id && it.accountId != null })
        }
    }

    @Test
    fun lateRealRateWriteFailureRollsBackRepaymentRowsLegsAndRatesInTheOuterRoomTransaction() = runBlocking {
        val debt = loan(DebtSide.I_OWE, "100", "USD")
        val headersBefore = db.entryDao().getAll()
        val legsBefore = db.entryDao().getAllLegs()
        val ratesBefore = db.rateDao().getAll()
        val failingSave = saveEntry(failAfterRateWrite("REPAYMENT"))

        try {
            withTimeout(RETURN_WITHIN_MS) {
                record(failingSave)(debt.id, USD_ACCOUNT, Money.of("40", "USD"), day,
                    typedRates = mapOf("SYP" to BigDecimal("0.0002")))
            }
            fail("expected late rate-write failure")
        } catch (_: LateWriteFault) {
            assertEquals(headersBefore, db.entryDao().getAll())
            assertEquals(legsBefore, db.entryDao().getAllLegs())
            assertEquals(ratesBefore, db.rateDao().getAll())
            assertEquals(debt, debts.findById(debt.id))
            assertEquals(Money.of("100", "USD"), remaining(debt))
        }
    }

    @Test
    fun lateRealRateWriteFailureRollsBackWriteOffRowsLegsAndRatesInTheOuterRoomTransaction() = runBlocking {
        val debt = loan(DebtSide.OWED_TO_ME, "1000050")
        val headersBefore = db.entryDao().getAll()
        val legsBefore = db.entryDao().getAllLegs()
        val ratesBefore = db.rateDao().getAll()
        val failingSave = saveEntry(failAfterRateWrite("WRITE_OFF"))

        try {
            withTimeout(RETURN_WITHIN_MS) {
                writeOff(failingSave)(debt.id, EXPENSE_CATEGORY, day,
                    typedRates = mapOf("SYP" to BigDecimal("0.0002")))
            }
            fail("expected late rate-write failure")
        } catch (_: LateWriteFault) {
            assertEquals(headersBefore, db.entryDao().getAll())
            assertEquals(legsBefore, db.entryDao().getAllLegs())
            assertEquals(ratesBefore, db.rateDao().getAll())
            assertEquals(debt, debts.findById(debt.id))
            assertEquals(Money.of("1000050", "SYP"), remaining(debt))
        }
    }

    /** The fault happens after both real writes, so unchanged snapshots prove rollback. */
    private fun failAfterRateWrite(expectedKind: String): RateRepository = object : RateRepository by rates {
        override suspend fun upsert(rate: ExchangeRate) {
            rates.upsert(rate)
            assertTrue("the outer Room transaction must still be active", db.inTransaction())
            assertEquals(1, db.entryDao().getAll().count { it.kind == expectedKind })
            assertEquals(0, rate.rateToBase.compareTo(rates.findByCode(rate.currencyCode)!!.rateToBase))
            throw LateWriteFault()
        }
    }

    private class LateWriteFault : RuntimeException("injected after real rate write")

    private companion object {
        const val RETURN_WITHIN_MS = 10_000L
        const val USD_ACCOUNT = 1L
        const val SYP_ACCOUNT = 2L
        const val EXPENSE_CATEGORY = 6L
        const val INCOME_CATEGORY = 7L
    }
}
