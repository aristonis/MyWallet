package org.aristonis.mywallet.data.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.aristonis.mywallet.data.db.AccountEntity
import org.aristonis.mywallet.data.db.CategoryEntity
import org.aristonis.mywallet.data.db.CurrencyEntity
import org.aristonis.mywallet.data.db.RateEntity
import org.aristonis.mywallet.data.db.SettingsEntity
import org.aristonis.mywallet.data.db.EntryEntity
import org.aristonis.mywallet.data.db.EntryLegEntity
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.data.db.scalar
import org.aristonis.mywallet.data.db.textRows
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
        CategoryEntity(id = 12, name = "Salary", kind = "INCOME"),
        CategoryEntity(id = 21, name = "Groceries", kind = "EXPENSE", parentId = 11),
    )
    private val accounts = listOf(
        AccountEntity(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalanceAmount = "100.50"),
        AccountEntity(id = 2, name = "Bank", typeKey = "card", currencyCode = "EUR", openingBalanceAmount = "0.00"),
    )
    private val rates = listOf(RateEntity(currencyCode = "EUR", rateToBase = "1.086956"))
    private val entries = listOf(
        EntryEntity(id = 1, kind = "INCOME", date = LocalDate.of(2026, 1, 5)),
        EntryEntity(id = 2, kind = "EXPENSE", date = LocalDate.of(2026, 1, 6), note = "lunch"),
        EntryEntity(id = 3, kind = "TRANSFER", date = LocalDate.of(2026, 1, 7), pairRate = "0.920000"),
    )

    /** Leg ids are assigned by the database and renumbered by a restore, so the seed leaves them at 0. */
    private val legs = listOf(
        EntryLegEntity(entryId = 1, role = "ACCOUNT", position = 0, accountId = 1, amount = "1234.00", currencyCode = "USD"),
        EntryLegEntity(entryId = 1, role = "MAIN", position = 0, categoryId = 12, amount = "1234.00", currencyCode = "USD", rateToBase = "1"),
        EntryLegEntity(entryId = 2, role = "ACCOUNT", position = 0, accountId = 1, amount = "-12.99", currencyCode = "USD"),
        EntryLegEntity(
            entryId = 2, role = "MAIN", position = 0, categoryId = 11, subCategoryId = 21, amount = "12.99",
            currencyCode = "USD", rateToBase = "1",
        ),
        EntryLegEntity(entryId = 3, role = "SOURCE", position = 0, accountId = 1, amount = "-20.000", currencyCode = "USD"),
        EntryLegEntity(entryId = 3, role = "DEST", position = 0, accountId = 2, amount = "18.40", currencyCode = "EUR"),
    )
    private val settings = SettingsEntity(baseCurrencyCode = "USD", theme = "SYSTEM", schemaVersion = 4)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WalletDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = newRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed() {
        db.currencyDao().insertAll(currencies)
        db.categoryDao().insertAll(categories)
        db.accountDao().insertAll(accounts)
        db.rateDao().insertAll(rates)
        db.settingsDao().upsert(settings)
        db.entryDao().insertAll(entries)
        db.entryDao().insertLegs(legs)
    }

    private suspend fun assertDatabaseMatchesSeed() {
        assertEquals(currencies.toSet(), db.currencyDao().getAll().toSet())
        assertEquals(categories.toSet(), db.categoryDao().getAll().toSet())
        assertEquals(accounts.toSet(), db.accountDao().getAll().toSet())
        assertEquals(rates.toSet(), db.rateDao().getAll().toSet())
        assertEquals(entries.toSet(), db.entryDao().getAll().toSet())
        assertEquals(legs.toSet(), db.entryDao().getAllLegs().map { it.copy(id = 0) }.toSet())
        assertEquals(settings, db.settingsDao().get())
    }

    private suspend fun wipeEverything() {
        db.entryDao().clear()
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
        val newerFormat = BackupCodec.encode(BackupCodec.decodeV2(repository.exportBackup()).copy(version = 4))

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
            BackupCodec.decodeV2(repository.exportBackup())
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
            BackupCodec.decodeV2(repository.exportBackup()).copy(settings = null),
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

    // --- Format 2 on the entries and legs tables ---

    private val openedExtra = mutableListOf<WalletDatabase>()

    @After
    fun closeExtraDatabases() = openedExtra.forEach { it.close() }

    /**
     * The only place the repository is constructed.
     * Assumed constructor: the old DAO list with `entryDao()` in place of `transactionDao()`, then
     * `categoryRoleDao()` and `legacyRowDao()`, then `debtDao()` and `deductionDao()`.
     */
    private fun newRepository(database: WalletDatabase) = RoomBackupRepository(
        database, database.accountDao(), database.entryDao(), database.categoryDao(),
        database.currencyDao(), database.rateDao(), database.settingsDao(),
        database.categoryRoleDao(), database.legacyRowDao(), database.debtDao(), database.deductionDao(),
        ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
    )

    private fun anotherDatabase(): WalletDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        WalletDatabase::class.java,
    ).allowMainThreadQueries().build().also { openedExtra += it }

    /** Every table, row by row as text, in a fixed order; leg ids are assigned by the restore, so they are left out. */
    private fun snapshot(database: WalletDatabase = db): List<List<List<String?>>> = SNAPSHOT_SQL.map {
        database.openHelper.writableDatabase.textRows(it)
    }

    private suspend fun restore(backup: WalletBackupV2, into: WalletDatabase = db) =
        newRepository(into).restoreBackup(BackupCodec.encode(backup))

    private suspend fun assertRejected(text: String, into: WalletDatabase = db) {
        val before = snapshot(into)

        var thrown: Throwable? = null
        try {
            newRepository(into).restoreBackup(text)
        } catch (e: WalletException.BackupInvalid) {
            thrown = e
        }

        assertTrue("expected BackupInvalid, got $thrown", thrown is WalletException.BackupInvalid)
        assertEquals("a rejected file must leave the wallet exactly as it was", before, snapshot(into))
    }

    private suspend fun assertRejected(backup: WalletBackupV2) = assertRejected(BackupCodec.encode(backup))

    private val sqlite get() = db.openHelper.writableDatabase

    /** Inserts one entry the way the app would and returns the id the database gave it. */
    private fun nextEntryId(database: WalletDatabase = db): Long {
        val target = database.openHelper.writableDatabase
        target.execSQL("INSERT INTO entries (kind, date) VALUES ('INCOME', 20454)")
        return requireNotNull(target.scalar("SELECT MAX(id) FROM entries")).toLong()
    }

    private fun assertSameContent(expected: WalletBackupV2, actual: WalletBackupV2) {
        assertEquals(CURRENT_BACKUP_VERSION, actual.version)
        assertEquals(expected.settings, actual.settings)
        assertEquals(expected.accounts.toSet(), actual.accounts.toSet())
        assertEquals(expected.categories.toSet(), actual.categories.toSet())
        assertEquals(expected.currencies.toSet(), actual.currencies.toSet())
        assertEquals(expected.rates.toSet(), actual.rates.toSet())
        assertEquals(expected.entries.toSet(), actual.entries.toSet())
        assertEquals(expected.legs.toSet(), actual.legs.toSet())
        assertEquals(expected.categoryRoles.toSet(), actual.categoryRoles.toSet())
        assertEquals(expected.legacyRows.toSet(), actual.legacyRows.toSet())
        assertEquals(expected.debts, actual.debts)
        assertEquals(expected.deductionItems, actual.deductionItems)
        assertEquals(expected.deductionAutoCategories, actual.deductionAutoCategories)
        assertEquals(expected.deductionLines, actual.deductionLines)
    }

    @Test
    fun export_writesFormatVersionThree() = runTest {
        restore(BackupV2Samples.wallet())

        val json = newRepository(db).exportBackup()

        assertEquals(3, CURRENT_BACKUP_VERSION)
        assertEquals(3, BackupCodec.decodeV2(json).version)
    }

    @Test
    fun formatTwo_roundTripRebuildsEveryTableOfTheExistingKindsWithAmountTextIntact() = runTest {
        val sample = BackupV2Samples.wallet()
        restore(sample)

        val exported = newRepository(db).exportBackup()
        assertSameContent(sample, BackupCodec.decodeV2(exported))

        val elsewhere = anotherDatabase()
        newRepository(elsewhere).restoreBackup(exported)
        assertEquals(snapshot(db), snapshot(elsewhere))
        assertEquals(
            "the transfer keeps its scale on both legs and its pair rate",
            listOf(listOf("DEST", "18.40"), listOf("SOURCE", "-20.000")),
            elsewhere.openHelper.writableDatabase.textRows("SELECT role, amount FROM entry_legs WHERE entryId = 3 ORDER BY role"),
        )
    }

    @Test
    fun restore_replacesEverythingThatWasThereIncludingRawCopies() = runTest {
        restore(BackupV2Samples.walletWithUnreadableRow(id = 4))
        assertEquals("1", sqlite.scalar("SELECT COUNT(*) FROM legacy_unreadable_rows"))

        restore(BackupV2Samples.wallet())

        assertEquals("0", sqlite.scalar("SELECT COUNT(*) FROM legacy_unreadable_rows"))
        assertEquals("3", sqlite.scalar("SELECT COUNT(*) FROM entries"))
    }

    @Test
    fun restore_ofAFormatOneFileFromTheLastRelease_convertsItAndKeepsEveryAmountAsText() = runTest {
        newRepository(db).restoreBackup(BackupV2Samples.versionOneText())

        assertEquals(
            listOf(listOf("1", "INCOME", null), listOf("2", "EXPENSE", null), listOf("3", "TRANSFER", "0.920000")),
            sqlite.textRows("SELECT id, kind, pairRate FROM entries ORDER BY id"),
        )
        assertEquals(
            listOf(
                listOf("1", "ACCOUNT", "1", null, null, "1234.00", "USD", null),
                listOf("1", "MAIN", null, "10", null, "1234.00", "USD", "1"),
                listOf("2", "ACCOUNT", "1", null, null, "-12.99", "USD", null),
                listOf("2", "MAIN", null, "11", "21", "12.99", "USD", "1"),
                listOf("3", "DEST", "2", null, null, "18.40", "EUR", null),
                listOf("3", "SOURCE", "1", null, null, "-20.000", "USD", null),
            ),
            sqlite.textRows(
                "SELECT entryId, role, accountId, categoryId, subCategoryId, amount, currencyCode, rateToBase " +
                    "FROM entry_legs ORDER BY entryId, role",
            ),
        )
        assertEquals("the three own categories plus the six pre-made ones", "9", sqlite.scalar("SELECT COUNT(*) FROM categories"))
        assertEquals("5", sqlite.scalar("SELECT COUNT(*) FROM category_roles"))
        assertEquals("0", sqlite.scalar("SELECT COUNT(*) FROM legacy_unreadable_rows"))
    }

    @Test
    fun restore_ofAFileWithAnEntryNoRecipeReads_isRejectedAndLeavesTheWalletUntouched() = runTest {
        restore(BackupV2Samples.wallet())
        val sample = BackupV2Samples.wallet()

        assertRejected(
            sample.copy(entries = sample.entries.map { if (it.id == 2L) it.copy(kind = "FROM_A_NEWER_BUILD") else it }),
        )
    }

    @Test
    fun restore_ofAFileWhoseEntryLacksALegItsKindNeeds_isRejectedAndLeavesTheWalletUntouched() = runTest {
        restore(BackupV2Samples.wallet())
        val sample = BackupV2Samples.wallet()

        assertRejected(sample.copy(legs = sample.legs.filterNot { it.entryId == 1L && it.role == "ACCOUNT" }))
    }

    // An upgraded wallet can hold an entry on the other kind of category or on a deleted category.
    // Its backup restores as it was; only saving such an entry anew asks for a correct category.

    private suspend fun assertRestoresAsItWas(file: WalletBackupV2) {
        restore(BackupV2Samples.wallet())

        restore(file)

        assertSameContent(file, BackupCodec.decodeV2(newRepository(db).exportBackup()))
    }

    @Test
    fun restore_ofAFileWithAnIncomeLegOnAnExpenseCategory_isAcceptedAndRestoresAsItWas() = runTest {
        val sample = BackupV2Samples.wallet()

        assertRestoresAsItWas(
            sample.copy(
                legs = sample.legs.map {
                    if (it.entryId == 1L && it.role == "MAIN") it.copy(categoryId = BackupV2Samples.FOOD) else it
                },
            ),
        )

        assertEquals(BackupV2Samples.FOOD.toString(), sqlite.scalar("SELECT categoryId FROM entry_legs WHERE entryId = 1 AND role = 'MAIN'"))
    }

    @Test
    fun restore_ofAFileWithAnExpenseLegOnAnIncomeCategory_isAcceptedAndRestoresAsItWas() = runTest {
        val sample = BackupV2Samples.wallet()

        assertRestoresAsItWas(
            sample.copy(
                legs = sample.legs.map {
                    if (it.entryId == 2L && it.role == "MAIN") {
                        it.copy(categoryId = BackupV2Samples.SALARY, subCategoryId = null)
                    } else {
                        it
                    }
                },
            ),
        )

        assertEquals(BackupV2Samples.SALARY.toString(), sqlite.scalar("SELECT categoryId FROM entry_legs WHERE entryId = 2 AND role = 'MAIN'"))
    }

    @Test
    fun restore_ofAFileWithACategoryLegOnACategoryThatDoesNotExist_isAcceptedAndRestoresAsItWas() = runTest {
        val sample = BackupV2Samples.wallet()

        assertRestoresAsItWas(
            sample.copy(
                legs = sample.legs.map { if (it.entryId == 1L && it.role == "MAIN") it.copy(categoryId = 999) else it },
            ),
        )

        assertEquals("999", sqlite.scalar("SELECT categoryId FROM entry_legs WHERE entryId = 1 AND role = 'MAIN'"))
    }

    @Test
    fun restore_ofAFormatOneFileWithRowsOnTheOtherKindOfCategoryAndOnAMissingCategory_restoresThemAsTheyWere() = runTest {
        val rows = BackupV2Samples.versionOneTransactions() + listOf(
            BackupV2Samples.versionOneTransaction(4, "EXPENSE", "2026-03-01", 1, "33.30", "USD", categoryId = BackupV2Samples.SALARY),
            BackupV2Samples.versionOneTransaction(5, "INCOME", "2026-03-02", 1, "14.10", "USD", categoryId = BackupV2Samples.FOOD),
            BackupV2Samples.versionOneTransaction(6, "INCOME", "2026-03-03", 1, "5.55", "USD", categoryId = 999),
        )

        newRepository(db).restoreBackup(BackupV2Samples.versionOneText(rows))

        assertEquals(
            listOf(listOf("4", "10"), listOf("5", "11"), listOf("6", "999")),
            sqlite.textRows("SELECT entryId, categoryId FROM entry_legs WHERE role = 'MAIN' AND entryId >= 4 ORDER BY entryId"),
        )
        assertEquals("0", sqlite.scalar("SELECT COUNT(*) FROM legacy_unreadable_rows"))
    }

    // --- Rows the unique indexes would refuse are BackupInvalid, and the wallet stays as it was ---

    @Test
    fun restore_ofAFileWithTwoLegsOfOneEntryAtTheSameRoleAndPosition_isRejectedAndLeavesTheWalletUntouched() = runTest {
        restore(BackupV2Samples.wallet())
        val sample = BackupV2Samples.wallet()

        assertRejected(sample.copy(legs = sample.legs + sample.legs.first { it.entryId == 1L && it.role == "ACCOUNT" }))
    }

    @Test
    fun restore_ofAFileWithTwoCategoriesOfOneNameInAnotherLetterCaseUnderOneParent_isRejectedAndLeavesTheWalletUntouched() = runTest {
        restore(BackupV2Samples.wallet())
        val sample = BackupV2Samples.wallet()

        assertRejected(sample.copy(categories = sample.categories + CategoryDto(id = 50, name = "FOOD", kind = "EXPENSE")))
        assertRejected(
            sample.copy(
                categories = sample.categories +
                    CategoryDto(id = 51, name = "groceries", kind = "EXPENSE", parentId = BackupV2Samples.FOOD),
            ),
        )
    }

    @Test
    fun restore_ofAFileWithTwoCategoriesOfOneSystemKey_isRejectedAndLeavesTheWalletUntouched() = runTest {
        restore(BackupV2Samples.wallet())
        val sample = BackupV2Samples.wallet()
        val keyed = listOf(
            CategoryDto(id = 50, name = "Uncategorized", kind = "EXPENSE", systemKey = "UNCATEGORIZED_EXPENSE"),
            CategoryDto(id = 51, name = "Other", kind = "EXPENSE", systemKey = "UNCATEGORIZED_EXPENSE"),
        )

        assertRejected(sample.copy(categories = sample.categories + keyed))
    }

    @Test
    fun restore_ofAFileFromANewerFormat_isRefusedAndLeavesTheWalletUntouched() = runTest {
        restore(BackupV2Samples.wallet())
        val before = snapshot()

        var thrown: Throwable? = null
        try {
            newRepository(db).restoreBackup(BackupCodec.encode(BackupV2Samples.wallet().copy(version = 4)))
        } catch (e: WalletException.BackupVersionUnsupported) {
            thrown = e
        }

        assertTrue("a version 4 file must be refused", thrown is WalletException.BackupVersionUnsupported)
        assertEquals(4, (thrown as WalletException.BackupVersionUnsupported).version)
        assertEquals(before, snapshot())
    }

    // --- Old unreadable rows survive export, clear data and restore ---

    @Test
    fun restore_ofAnUnreadableEntryWithItsRawCopy_keepsBothAndGivesTheEntryNoLegs() = runTest {
        listOf("UNKNOWN_TYPE", "MISSING_CATEGORY", "MISSING_DESTINATION").forEach { reason ->
            restore(BackupV2Samples.walletWithUnreadableRow(id = 4, reason = reason))

            assertEquals(reason, listOf(listOf("4", "LEGACY_UNREADABLE")), sqlite.textRows("SELECT id, kind FROM entries WHERE id = 4"))
            assertEquals(reason, "0", sqlite.scalar("SELECT COUNT(*) FROM entry_legs WHERE entryId = 4"))
            assertEquals(
                "the raw copy is written back verbatim",
                listOf(
                    listOf(
                        "4", "REFUND", LocalDate.of(2026, 2, 1).toEpochDay().toString(), "odd row", "1", "10.00", "USD",
                        "11", null, null, null, null, null, reason,
                    ),
                ),
                sqlite.textRows(RAW_COPIES_SQL),
            )
        }
    }

    @Test
    fun restore_ofAnUnreadableEntryWithNoRawCopy_isRejected() = runTest {
        restore(BackupV2Samples.wallet())
        val withEntryOnly = BackupV2Samples.wallet().let { it.copy(entries = it.entries + BackupV2Samples.unreadableEntry(4)) }

        assertRejected(withEntryOnly)
    }

    @Test
    fun restore_ofAnUnreadableEntryWhoseRawCopyHasAReasonThatKeepsTheRowReadable_isRejected() = runTest {
        restore(BackupV2Samples.wallet())

        assertRejected(BackupV2Samples.walletWithUnreadableRow(id = 4, reason = "IGNORED_COLUMNS"))
    }

    @Test
    fun restore_ofAnUnreadableEntryWhoseRawCopyHasAnUnknownReason_isRejected() = runTest {
        restore(BackupV2Samples.wallet())

        assertRejected(BackupV2Samples.walletWithUnreadableRow(id = 4, reason = "SOMETHING_ELSE"))
    }

    @Test
    fun restore_ofAnUnreadableEntryThatHasLegs_isRejected() = runTest {
        restore(BackupV2Samples.wallet())
        val withLeg = BackupV2Samples.walletWithUnreadableRow(id = 4).let {
            it.copy(legs = it.legs + BackupV2Samples.account(4, "ACCOUNT", BackupV2Samples.CASH, "10.00", "USD"))
        }

        assertRejected(withLeg)
    }

    @Test
    fun restore_ofAFileWithTwoRawCopiesOfOneId_isRejectedBeforeAnythingIsWritten() = runTest {
        restore(BackupV2Samples.wallet())
        val twice = BackupV2Samples.walletWithUnreadableRow(id = 4).let {
            it.copy(legacyRows = it.legacyRows + BackupV2Samples.rawCopy(4, "MISSING_CATEGORY"))
        }

        assertRejected(twice)
    }

    @Test
    fun restore_ofARawCopyWithNoEntry_isAcceptedBecauseTheOwnerMayHaveDeletedTheEntry() = runTest {
        restore(BackupV2Samples.wallet().copy(legacyRows = listOf(BackupV2Samples.rawCopy(40))))

        assertEquals(listOf(listOf("40")), sqlite.textRows("SELECT id FROM legacy_unreadable_rows"))
        assertEquals("0", sqlite.scalar("SELECT COUNT(*) FROM entries WHERE id = 40"))
    }

    @Test
    fun restore_ofARawCopyThatNoLongerMatchesItsEntry_isAcceptedAndWrittenAsItIs() = runTest {
        // Entry 1 is a readable income now; the copy is the upgrade-day record of an expense row.
        val stale = BackupV2Samples.rawCopy(id = 1, reason = "IGNORED_COLUMNS", type = "EXPENSE")

        restore(BackupV2Samples.wallet().copy(legacyRows = listOf(stale)))

        assertEquals("INCOME", sqlite.scalar("SELECT kind FROM entries WHERE id = 1"))
        assertEquals(listOf(listOf("1", "EXPENSE", "IGNORED_COLUMNS")), sqlite.textRows("SELECT id, type, reason FROM legacy_unreadable_rows"))
    }

    // --- Ids are never reused after a restore ---

    @Test
    fun afterARestore_theNextEntryGetsTheIdAfterTheHighestEntryId() = runTest {
        restore(BackupV2Samples.wallet())

        assertEquals(4L, nextEntryId())
    }

    @Test
    fun afterARestore_theNextEntryGetsTheIdAfterTheHighestRawCopyWhenThatIsHigher() = runTest {
        // Entry 40 was deleted after the upgrade; its raw copy remains, and its id must not come back.
        restore(BackupV2Samples.wallet().copy(legacyRows = listOf(BackupV2Samples.rawCopy(40))))

        assertEquals(41L, nextEntryId())
    }

    @Test
    fun afterARestore_theNextEntryGetsTheIdAfterTheHighestEntryIdWhenThatIsHigher() = runTest {
        restore(BackupV2Samples.wallet().copy(legacyRows = listOf(BackupV2Samples.rawCopy(2, reason = "IGNORED_COLUMNS", type = "EXPENSE"))))

        assertEquals(4L, nextEntryId())
    }

    @Test
    fun afterARestoreWithNoEntries_theNextEntryGetsTheIdAfterTheHighestRawCopy() = runTest {
        val bare = BackupV2Samples.wallet().copy(entries = emptyList(), legs = emptyList(), legacyRows = listOf(BackupV2Samples.rawCopy(7)))

        restore(bare)

        assertEquals(8L, nextEntryId())
    }

    // --- Format-1 files are converted first, then only the format-2 checks run ---

    @Test
    fun restore_ofAFormatOneFileHoldingAnUnknownTypeRow_restoresItAsAnUnreadableEntryWithItsRawCopy() = runTest {
        val oddRow = BackupV2Samples.versionOneTransaction(14, "REFUND", "2026-03-01", 1, "10.00", "USD", categoryId = BackupV2Samples.FOOD)

        newRepository(db).restoreBackup(BackupV2Samples.versionOneText(BackupV2Samples.versionOneTransactions() + oddRow))

        assertEquals(listOf(listOf("14", "LEGACY_UNREADABLE")), sqlite.textRows("SELECT id, kind FROM entries WHERE id = 14"))
        assertEquals("0", sqlite.scalar("SELECT COUNT(*) FROM entry_legs WHERE entryId = 14"))
        assertEquals(listOf(listOf("14", "REFUND", "UNKNOWN_TYPE")), sqlite.textRows("SELECT id, type, reason FROM legacy_unreadable_rows"))
        assertEquals("the next id is above the unreadable row's", 15L, nextEntryId())
    }

    @Test
    fun restore_ofAFormatOneFileTheConverterRefuses_isBackupInvalidNotACrashAndLeavesTheWalletUntouched() = runTest {
        restore(BackupV2Samples.wallet())
        val twoRowsWithOneId = BackupV2Samples.versionOneTransactions() + BackupV2Samples.versionOneTransactions().first()

        assertRejected(BackupV2Samples.versionOneText(twoRowsWithOneId))
    }

    @Test
    fun freshRestoreKeepsPaidAndOwedDeductionsFeesLimitsAndStoredRates() = runTest {
        val base = BackupV2Samples.wallet()
        val incomeId = BackupV2Samples.INCOME_ENTRY
        val rich = base.copy(
            accounts = base.accounts.map {
                if (it.id == BackupV2Samples.CASH) {
                    it.copy(typeKey = "credit_card", creditLimit = "1500.00",
                        defaultFeeMode = "PERCENT", defaultFeeValue = "2.50")
                } else it
            },
            debts = listOf(DebtDto(8, "Family", "family", "I_OWE", "USD", null, "saved debt")),
            deductionItems = listOf(
                DeductionItemDto(5, "Tax", "tax", "PERCENT", "10.00", null, "NOW", 35, true, 0),
                DeductionItemDto(6, "Family", "family", "FIXED", "50.000", "USD", "LATER", 35, true, 1),
            ),
            deductionAutoCategories = listOf(DeductionAutoCategoryDto(5, BackupV2Samples.SALARY)),
            deductionLines = listOf(
                DeductionLineDto(1, incomeId, 5, 0, "Tax as paid", "PERCENT", "10.00", null, "NOW"),
                DeductionLineDto(2, incomeId, 6, 1, "Family as owed", "FIXED", "50.000", "USD", "LATER"),
            ),
            legs = base.legs.map {
                when {
                    it.entryId == incomeId && it.role == "ACCOUNT" -> it.copy(amount = "1110.60")
                    it.entryId == 3L && it.role == "DEST" -> it.copy(amount = "17.94")
                    else -> it
                }
            } + listOf(
                LegDto(incomeId, "DEDUCTION", 0, null, null, 35, null, "123.40", "USD", "1.000"),
                LegDto(incomeId, "DEDUCTION", 1, null, null, 35, null, "50.00", "USD", "1.000"),
                LegDto(incomeId, "DEDUCTION_DEBT", 1, null, 8, null, null, "50.00", "USD", null),
                LegDto(3, "FEE", 0, null, null, 32, null, "0.50", "USD", "1.000"),
            ),
        )
        restore(rich)
        val exported = repository.exportBackup()
        val fresh = anotherDatabase()
        newRepository(fresh).restoreBackup(exported)
        assertSameContent(rich, BackupCodec.decodeV2(newRepository(fresh).exportBackup()))
        assertEquals(snapshot(), snapshot(fresh))
    }

    @Test
    fun exportRefusesInvalidStoredFeeBeforeAnyDocumentIsOpened() = runTest {
        restore(BackupV2Samples.wallet())
        sqlite.execSQL("UPDATE accounts SET defaultFeeMode = 'PERCENT', defaultFeeValue = '101' WHERE id = 1")
        var thrown: Throwable? = null
        try {
            // Preparing takes no stream: the refusal comes before there is anything to write to.
            repository.prepareBackup()
        } catch (error: WalletException.BackupInvalid) {
            thrown = error
        }
        assertTrue(thrown is WalletException.BackupInvalid)
    }

    @Test
    fun formatThreeIncomeFeeAndPaidLaterExpenseRestoreIdenticalBalancesDebtsAndTracking() = runTest {
        val base = BackupV2Samples.versionThreeWallet()
        val rich = base.copy(
            debts = listOf(DebtDto(8, "Shop", "shop", "I_OWE", "USD", null, null)),
            legs = base.legs.filterNot { it.entryId == 2L }.map {
                if (it.entryId == 1L && it.role == "ACCOUNT") it.copy(amount = "1214.00") else it
            } + listOf(
                LegDto(1, "FEE", 0, null, null, BackupV2Samples.FEES, null, "20.00", "USD", "1"),
                BackupV2Samples.account(2, "ACCOUNT", BackupV2Samples.CASH, "-303.00", "USD"),
                BackupV2Samples.main(2, BackupV2Samples.FOOD, BackupV2Samples.GROCERIES, "300.00", "USD", "1"),
                LegDto(2, "FEE", 0, null, null, BackupV2Samples.FEES, null, "3.00", "USD", "1"),
                LegDto(2, "PAID_LATER", 0, null, null, BackupV2Samples.FOOD, BackupV2Samples.GROCERIES, "700.00", "USD", "1"),
                LegDto(2, "DEBT", 0, null, 8, null, null, "700.00", "USD", null),
            ),
        )
        restore(rich)
        val original = org.aristonis.mywallet.data.repo.RoomLedgerQueries(db.ledgerQueryDao())
        val fresh = anotherDatabase()

        newRepository(fresh).restoreBackup(repository.exportBackup())
        val restored = org.aristonis.mywallet.data.repo.RoomLedgerQueries(fresh.ledgerQueryDao())

        assertSameContent(rich, BackupCodec.decodeV2(newRepository(fresh).exportBackup()))
        assertEquals(snapshot(), snapshot(fresh))
        assertEquals(original.observeAccountLegs().first(), restored.observeAccountLegs().first())
        assertEquals(original.observeDebtLegs().first(), restored.observeDebtLegs().first())
        assertEquals(original.observeCategoryLegs(org.aristonis.mywallet.domain.model.DateRange.ALL_TIME).first(),
            restored.observeCategoryLegs(org.aristonis.mywallet.domain.model.DateRange.ALL_TIME).first())
        assertEquals("700.00", fresh.entryDao().getAllLegs().single { it.role == "PAID_LATER" }.amount)
    }

    private companion object {
        const val RAW_COPIES_SQL =
            "SELECT id, type, date, note, primaryAccountId, primaryAmount, primaryCurrency, categoryId, subCategoryId, " +
                "secondaryAccountId, secondaryAmount, secondaryCurrency, rateUsed, reason FROM legacy_unreadable_rows"

        val SNAPSHOT_SQL = listOf(
            "SELECT * FROM settings ORDER BY id",
            "SELECT * FROM currencies ORDER BY code",
            "SELECT * FROM rates ORDER BY currencyCode",
            "SELECT * FROM accounts ORDER BY id",
            "SELECT * FROM categories ORDER BY id",
            "SELECT * FROM entries ORDER BY id",
            "SELECT entryId, role, position, accountId, debtId, categoryId, subCategoryId, amount, currencyCode, rateToBase " +
                "FROM entry_legs ORDER BY entryId, role, position",
            "SELECT * FROM debts ORDER BY id",
            "SELECT * FROM deduction_items ORDER BY id",
            "SELECT * FROM deduction_item_auto_categories ORDER BY itemId, incomeCategoryId",
            "SELECT * FROM income_deduction_lines ORDER BY id",
            "SELECT * FROM category_roles ORDER BY role",
            RAW_COPIES_SQL + " ORDER BY id",
        )
    }
}

/** Fixture convenience only; production callers pass their document streams directly. */
private suspend fun RoomBackupRepository.exportBackup(): String {
    val output = java.io.ByteArrayOutputStream()
    prepareBackup().writeTo(output)
    return output.toString("UTF-8")
}

private suspend fun RoomBackupRepository.restoreBackup(text: String) {
    text.byteInputStream().use { restoreBackup(it) }
}
