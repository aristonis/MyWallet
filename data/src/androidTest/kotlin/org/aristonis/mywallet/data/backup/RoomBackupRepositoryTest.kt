package org.aristonis.mywallet.data.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.data.db.AccountEntity
import org.aristonis.mywallet.data.db.CategoryEntity
import org.aristonis.mywallet.data.db.CurrencyEntity
import org.aristonis.mywallet.data.db.RateEntity
import org.aristonis.mywallet.data.db.SettingsEntity
import org.aristonis.mywallet.data.db.TransactionEntity
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.domain.error.WalletException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Drives the real Room adapter against an in-memory database: an exported snapshot must restore
 * every table byte-for-byte (ids preserved), and a rejected input must leave the existing data
 * completely untouched — proving the restore never wipes before it has a valid backup in hand.
 */
@RunWith(AndroidJUnit4::class)
class RoomBackupRepositoryTest {

    private lateinit var db: WalletDatabase
    private lateinit var repository: RoomBackupRepository

    private val currencies = listOf(
        CurrencyEntity(code = "USD", symbol = "$", decimalPlaces = 2),
        CurrencyEntity(code = "EUR", symbol = "€", decimalPlaces = 2),
    )
    private val categories = listOf(
        CategoryEntity(id = 11, name = "Food", kind = "EXPENSE"),
        CategoryEntity(id = 21, name = "Groceries", kind = "EXPENSE", parentId = 11),
    )
    private val accounts = listOf(
        AccountEntity(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalanceAmount = "100.50"),
        AccountEntity(id = 2, name = "Bank", typeKey = "card", currencyCode = "EUR", openingBalanceAmount = "0.00"),
    )
    private val rates = listOf(RateEntity(currencyCode = "EUR", rateToBase = "1.086956"))
    private val transactions = listOf(
        TransactionEntity(
            id = 1, type = "INCOME", date = LocalDate.of(2026, 1, 5),
            primaryAccountId = 1, primaryAmount = "1234.00", primaryCurrency = "USD", categoryId = 11,
        ),
        TransactionEntity(
            id = 2, type = "EXPENSE", date = LocalDate.of(2026, 1, 6), note = "lunch",
            primaryAccountId = 1, primaryAmount = "12.99", primaryCurrency = "USD",
            categoryId = 11, subCategoryId = 21,
        ),
        TransactionEntity(
            id = 3, type = "TRANSFER", date = LocalDate.of(2026, 1, 7),
            primaryAccountId = 1, primaryAmount = "20.000", primaryCurrency = "USD",
            secondaryAccountId = 2, secondaryAmount = "18.40", secondaryCurrency = "EUR",
            rateUsed = "0.920000",
        ),
    )
    private val settings = SettingsEntity(baseCurrencyCode = "USD", theme = "SYSTEM", schemaVersion = 4)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WalletDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomBackupRepository(
            db, db.accountDao(), db.transactionDao(), db.categoryDao(),
            db.currencyDao(), db.rateDao(), db.settingsDao(),
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed() {
        db.currencyDao().insertAll(currencies)
        db.categoryDao().insertAll(categories)
        db.accountDao().insertAll(accounts)
        db.rateDao().insertAll(rates)
        db.settingsDao().upsert(settings)
        db.transactionDao().insertAll(transactions)
    }

    private suspend fun assertDatabaseMatchesSeed() {
        assertEquals(currencies.toSet(), db.currencyDao().getAll().toSet())
        assertEquals(categories.toSet(), db.categoryDao().getAll().toSet())
        assertEquals(accounts.toSet(), db.accountDao().getAll().toSet())
        assertEquals(rates.toSet(), db.rateDao().getAll().toSet())
        assertEquals(transactions.toSet(), db.transactionDao().getAll().toSet())
        assertEquals(settings, db.settingsDao().get())
    }

    private suspend fun wipeEverything() {
        db.transactionDao().clear()
        db.settingsDao().clear()
        db.rateDao().clear()
        db.accountDao().clear()
        db.categoryDao().clear()
        db.currencyDao().clear()
    }

    @Test
    fun exportThenRestore_rebuildsEveryTableWithIdsPreserved() = runTest {
        seed()
        val json = repository.exportBackup()

        wipeEverything()
        repository.restoreBackup(json)

        assertDatabaseMatchesSeed()
    }

    @Test
    fun restore_ofCorruptInput_throwsAndLeavesTheDatabaseUntouched() = runTest {
        seed()

        var thrown: Throwable? = null
        try {
            repository.restoreBackup("{ not valid json")
        } catch (e: WalletException.BackupInvalid) {
            thrown = e
        }

        assertTrue("corrupt input must surface as BackupInvalid", thrown is WalletException.BackupInvalid)
        assertDatabaseMatchesSeed()
    }

    @Test
    fun restore_ofUnsupportedVersion_throwsAndLeavesTheDatabaseUntouched() = runTest {
        seed()
        val newerFormat = BackupCodec.encode(BackupCodec.decode(repository.exportBackup()).copy(version = 2))

        var thrown: Throwable? = null
        try {
            repository.restoreBackup(newerFormat)
        } catch (e: WalletException.BackupVersionUnsupported) {
            thrown = e
        }

        assertTrue("newer format must surface as BackupVersionUnsupported", thrown is WalletException.BackupVersionUnsupported)
        assertDatabaseMatchesSeed()
    }

    @Test
    fun restore_ofBackupWithADuplicateId_rollsBackLeavingTheSeedIntact() = runTest {
        seed()
        // Two accounts sharing a primary key make insertAll abort partway through the restore. This
        // is the headline guarantee: the wipe and any rows already inserted must roll back, so the
        // database is never left half-restored.
        val corruptJson = BackupCodec.encode(
            BackupCodec.decode(repository.exportBackup())
                .let { it.copy(accounts = it.accounts + it.accounts.first()) },
        )

        var thrown: Throwable? = null
        try {
            repository.restoreBackup(corruptJson)
        } catch (e: Throwable) {
            thrown = e
        }

        assertTrue("a duplicate id must abort the restore", thrown != null)
        assertDatabaseMatchesSeed()
    }

    @Test
    fun restore_ofNullSettingsBackup_isRejectedAndLeavesTheDatabaseUntouched() = runTest {
        seed()
        val noSettings = BackupCodec.encode(
            BackupCodec.decode(repository.exportBackup()).copy(settings = null),
        )

        var thrown: Throwable? = null
        try {
            repository.restoreBackup(noSettings)
        } catch (e: WalletException.BackupInvalid) {
            thrown = e
        }

        assertTrue("a settings-less backup must be rejected", thrown is WalletException.BackupInvalid)
        assertDatabaseMatchesSeed()
    }
}
