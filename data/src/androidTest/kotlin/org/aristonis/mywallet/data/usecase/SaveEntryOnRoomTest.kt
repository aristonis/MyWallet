package org.aristonis.mywallet.data.usecase

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
import org.aristonis.mywallet.data.repo.RoomAccountRepository
import org.aristonis.mywallet.data.repo.RoomCategoryRepository
import org.aristonis.mywallet.data.repo.RoomCurrencyRepository
import org.aristonis.mywallet.data.repo.RoomEntryRepository
import org.aristonis.mywallet.data.repo.RoomFxRepository
import org.aristonis.mywallet.data.repo.RoomLedgerQueries
import org.aristonis.mywallet.data.repo.RoomRateRepository
import org.aristonis.mywallet.data.repo.RoomSettingsRepository
import org.aristonis.mywallet.data.repo.RoomTransactionRunner
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
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
 * constructors `RestoreEntries(runner, entries, categories, accounts)` (accounts last, as in the
 * in-memory rig).
 */
@RunWith(AndroidJUnit4::class)
class SaveEntryOnRoomTest {

    private lateinit var db: WalletDatabase
    private lateinit var entries: RoomEntryRepository
    private lateinit var rates: RoomRateRepository
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
        saveEntry = SaveEntry(
            runner = runner,
            accounts = accounts,
            categories = categories,
            entries = entries,
            fx = RoomFxRepository(db),
            settings = RoomSettingsRepository(db.settingsDao()),
            setExchangeRate = SetExchangeRate(RoomCurrencyRepository(db.currencyDao()), rates, ledger, runner, RoomSettingsRepository(db.settingsDao())),
        )
        deleteEntry = DeleteEntry(runner = runner, entries = entries)
        restoreEntries = RestoreEntries(runner = runner, entries = entries, categories = categories, accounts = accounts)
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

    private companion object {
        const val RETURN_WITHIN_MS = 10_000L
    }
}
