package org.aristonis.mywallet.data.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aristonis.mywallet.data.db.AccountEntity
import org.aristonis.mywallet.data.db.CategoryEntity
import org.aristonis.mywallet.data.db.CurrencyEntity
import org.aristonis.mywallet.data.db.RateEntity
import org.aristonis.mywallet.data.db.SettingsEntity
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
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.DebtRef
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.service.RoleCategories
import org.aristonis.mywallet.domain.usecase.DeleteCategory
import org.aristonis.mywallet.domain.usecase.DeleteEntry
import org.aristonis.mywallet.domain.usecase.RestoreEntries
import org.aristonis.mywallet.domain.usecase.SaveEntry
import org.aristonis.mywallet.domain.usecase.SetExchangeRate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate

/**
 * SaveEntry, DeleteEntry and RestoreEntries wired the way the app wires them: the real Room runner and
 * Room repositories over an in-memory database. A fake runner cannot show the two failures that only
 * exist here: a Room Flow collected inside the write transaction that never returns, and an undo
 * that lands on a taken id.
 *
 * Assumed: `RoomFxRepository(db)`, `RoomLedgerQueries(db.ledgerQueryDao())` and the use-case
 * uses the same required reference repositories as the in-memory rig.
 */
@RunWith(AndroidJUnit4::class)
class SaveEntryOnRoomTest {

    private lateinit var db: WalletDatabase
    private lateinit var entries: RoomEntryRepository
    private lateinit var rates: RoomRateRepository
    private lateinit var debts: RoomDebtRepository
    private lateinit var saveEntry: SaveEntry
    private lateinit var deleteEntry: DeleteEntry
    private lateinit var restoreEntries: RestoreEntries

    private val day = LocalDate.of(2026, 10, 9)

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WalletDatabase::class.java,
        ).allowMainThreadQueries().build()

        db.currencyDao().insertAll(
            listOf(CurrencyEntity("USD", "$", 2), CurrencyEntity("SYP", "£S", 2)),
        )
        db.rateDao().insertAll(listOf(RateEntity(currencyCode = "SYP", rateToBase = "0.0001")))
        db.settingsDao().upsert(
            SettingsEntity(baseCurrencyCode = "USD", theme = "SYSTEM", schemaVersion = 1, saveTransactionRates = true),
        )
        db.accountDao().insertAll(
            listOf(
                AccountEntity(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalanceAmount = "0"),
                AccountEntity(id = 2, name = "Wallet", typeKey = "cash", currencyCode = "SYP", openingBalanceAmount = "0"),
            ),
        )
        db.categoryDao().insertAll(listOf(CategoryEntity(id = 6, name = "Food", kind = "EXPENSE")))

        val runner = RoomTransactionRunner(db)
        val categories = RoomCategoryRepository(db.categoryDao())
        val accounts = RoomAccountRepository(db.accountDao())
        entries = RoomEntryRepository(db, db.entryDao())
        rates = RoomRateRepository(db.rateDao())

        val ledger = RoomLedgerQueries(db.ledgerQueryDao())
        debts = RoomDebtRepository(db)
        saveEntry = SaveEntry(
            runner = runner,
            accounts = accounts,
            categories = categories,
            entries = entries,
            fx = RoomFxRepository(db),
            settings = RoomSettingsRepository(db.settingsDao()),
            setExchangeRate = SetExchangeRate(RoomCurrencyRepository(db.currencyDao()), rates, ledger, runner, RoomSettingsRepository(db.settingsDao())),
            debts = debts,
            ledger = ledger,
            roleCategories = RoleCategories(RoomCategoryRoleRepository(db.categoryRoleDao()), categories),
            deductions = RoomDeductionItemRepository(db),
        )
        deleteEntry = DeleteEntry(runner = runner, entries = entries, debts = debts, ledger = ledger)
        restoreEntries = RestoreEntries(runner = runner, entries = entries, categories = categories,
            accounts = accounts, debts = debts, ledger = ledger, deductions = RoomDeductionItemRepository(db))
    }

    @After
    fun tearDown() = db.close()

    private fun sypExpense(id: Long = 0) = Transaction.Expense(
        id = id,
        accountId = 2,
        amount = Money.of("5000", "SYP"),
        categoryId = 6,
        date = day,
    )

    @Test
    fun aNewExpenseWithATypedRateSavesTheEntryAndTheRateAndReturns() = runBlocking {
        val saved = withTimeout(RETURN_WITHIN_MS) {
            saveEntry(sypExpense(), typedRates = mapOf("SYP" to BigDecimal("0.0002")))
        }

        val stored = entries.findById(saved.id) as Transaction.Expense
        assertEquals(0, BigDecimal("0.0002").compareTo(stored.rateToBase))
        assertEquals(0, BigDecimal("0.0002").compareTo(rates.findByCode("SYP")!!.rateToBase))
    }

    @Test
    fun deleteThenRestorePutsTheEntryBackAtTheSameId() = runBlocking {
        val saved = withTimeout(RETURN_WITHIN_MS) { saveEntry(sypExpense(), typedRates = emptyMap()) }
        val before = entries.findById(saved.id)

        val bundle = withTimeout(RETURN_WITHIN_MS) { deleteEntry(saved.id) }
        assertNull(entries.findById(saved.id))
        withTimeout(RETURN_WITHIN_MS) { restoreEntries(bundle) }

        assertNotNull(before)
        assertEquals(before, entries.findById(saved.id))
    }

    @Test
    fun restoringTheSameBundleTwiceIsRefusedWithATakenIdError() = runBlocking {
        val saved = withTimeout(RETURN_WITHIN_MS) { saveEntry(sypExpense(), typedRates = emptyMap()) }
        val bundle = deleteEntry(saved.id)
        restoreEntries(bundle)

        val failure = runCatching { restoreEntries(bundle) }.exceptionOrNull()

        assertTrue("was $failure", failure is WalletException.EntryIdTaken)
        assertEquals(saved.id, (failure as WalletException.EntryIdTaken).id)
        assertNotNull(entries.findById(saved.id))
    }

    private fun borrow(debtId: Long = 0) = Transaction.Borrow(
        accountId = 1, debtId = debtId, amount = Money.of("100", "USD"), date = day,
    )

    private fun repayment(debtId: Long, amount: String = "100", interest: String? = null) = Transaction.Repayment(
        side = DebtSide.I_OWE, accountId = 1, debtId = debtId,
        accountAmount = Money.of(BigDecimal(amount).add(interest?.let(::BigDecimal) ?: BigDecimal.ZERO), "USD"),
        principal = Money.of(amount, "USD"), interest = interest?.let { Money.of(it, "USD") }, date = day,
    )

    @Test
    fun savingADebtAndItsGuardReturnsInsideTheRoomWriteTransaction() = runBlocking {
        val saved = withTimeout(RETURN_WITHIN_MS) {
            saveEntry(borrow(), emptyMap(), DebtRef(" Ali ", DebtSide.I_OWE, "USD"))
        }
        val loan = entries.findById(saved.id) as Transaction.Borrow
        assertEquals("Ali", debts.findById(loan.debtId)!!.counterparty)
        withTimeout(RETURN_WITHIN_MS) { saveEntry(repayment(loan.debtId), emptyMap()) }
        val failure = runCatching { withTimeout(RETURN_WITHIN_MS) { deleteEntry(saved.id) } }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
        assertEquals(loan, entries.findById(saved.id))
    }

    @Test
    fun aRefusedRepaymentRollsBackItsFallbackBucketAndLegs() = runBlocking {
        val saved = saveEntry(borrow(), emptyMap(), DebtRef("Ali", DebtSide.I_OWE, "USD"))
        val loan = entries.findById(saved.id) as Transaction.Borrow
        val categoriesBefore = db.categoryDao().getAll()
        val failure = runCatching {
            withTimeout(RETURN_WITHIN_MS) { saveEntry(repayment(loan.debtId, "101", "5"), emptyMap()) }
        }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
        assertEquals(categoriesBefore, db.categoryDao().getAll())
        assertEquals(1, db.entryDao().getAll().size)
    }

    @Test
    fun aFaultAfterTheRoomInsertRollsBackTheNewDebtAndEntry() = runBlocking {
        val runner = RoomTransactionRunner(db)
        val ledger = RoomLedgerQueries(db.ledgerQueryDao())
        val categories = RoomCategoryRepository(db.categoryDao())
        val failureAfterInsert = object : EntryRepository by entries {
            override suspend fun insert(entry: Transaction): Long {
                entries.insert(entry)
                error("injected after Room insert")
            }
        }
        val failingSave = SaveEntry(
            runner, RoomAccountRepository(db.accountDao()), categories, failureAfterInsert,
            RoomFxRepository(db), RoomSettingsRepository(db.settingsDao()),
            SetExchangeRate(RoomCurrencyRepository(db.currencyDao()), rates, ledger, runner, RoomSettingsRepository(db.settingsDao())),
            debts, ledger, RoleCategories(RoomCategoryRoleRepository(db.categoryRoleDao()), categories),
            RoomDeductionItemRepository(db),
        )
        val failure = runCatching {
            withTimeout(RETURN_WITHIN_MS) { failingSave(borrow(), emptyMap(), DebtRef("Ali", DebtSide.I_OWE, "USD")) }
        }.exceptionOrNull()
        assertEquals("injected after Room insert", failure?.message)
        assertTrue(db.debtDao().getAll().isEmpty())
        assertTrue(db.entryDao().getAll().isEmpty())
    }

    @Test
    fun aRefundRemainsReadableAfterItsExpenseCategoryIsDeleted() = runBlocking {
        val refund = Transaction.Refund(accountId = 1, amount = Money.of("5.00", "USD"), categoryId = 6, date = day)
        val saved = saveEntry(refund, emptyMap())
        val categories = RoomCategoryRepository(db.categoryDao())
        assertEquals(1, DeleteCategory(categories)(6))
        val bucket = requireNotNull(
            db.categoryDao().findBySystemKey(Category.uncategorizedKeyFor(CategoryKind.EXPENSE)),
        )
        val restored = entries.findById(saved.id) as Transaction.Refund
        assertEquals(bucket.id, restored.categoryId)
        assertEquals(refund.amount, restored.amount)
        assertNull(categories.findById(6))
        val storedLegs = db.entryDao().getAllLegs().filter { it.entryId == saved.id }
        assertEquals(Money.of("5", "USD"), Money.of(storedLegs.single { it.role == "ACCOUNT" }.amount, "USD"))
        assertEquals(Money.of("-5", "USD"), Money.of(storedLegs.single { it.role == "MAIN" }.amount, "USD"))
    }

    @Test
    fun concurrentSavesResolveOneFallbackBucketWithoutLosingEitherEntry() = runBlocking {
        val start = CompletableDeferred<Unit>()
        val draft = Transaction.Expense(accountId = 1, amount = Money.of("10", "USD"), categoryId = 6,
            date = day, fee = Money.of("1", "USD"))
        val first = async(Dispatchers.Default) { start.await(); saveEntry(draft, emptyMap()) }
        val second = async(Dispatchers.Default) { start.await(); saveEntry(draft, emptyMap()) }
        start.complete(Unit)
        val saved = withTimeout(RETURN_WITHIN_MS) { listOf(first.await(), second.await()) }
        assertEquals(2, saved.map { it.id }.toSet().size)
        val buckets = db.categoryDao().getAll().filter {
            it.systemKey == Category.uncategorizedKeyFor(CategoryKind.EXPENSE)
        }
        assertEquals(1, buckets.size)
        val fees = saved.map { entries.findById(it.id) as Transaction.Expense }
        assertTrue(fees.all { it.feeCategoryId == buckets.single().id && it.fee == Money.of("1", "USD") })
        assertEquals(2, db.entryDao().getAll().size)
        assertEquals(2, db.entryDao().getAllLegs().count { it.role == "FEE" })
    }

    private companion object {
        const val RETURN_WITHIN_MS = 10_000L
    }
}
