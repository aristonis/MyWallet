package org.aristonis.mywallet.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.data.backup.BackupCodec
import org.aristonis.mywallet.data.backup.RoomBackupRepository
import org.aristonis.mywallet.data.fixture.LegacyFixture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A wallet upgraded from the last release has to be able to save a backup that a restore takes back.
 * The upgrade keeps every old row exactly as it was, including entries filed on the other kind of
 * category and entries whose category was deleted, so the backup of such a wallet holds them too.
 *
 * The wallet here is the shared schema-2 fixture upgraded by the real migration, opened through the
 * real Room database, exported and restored by the real backup repository.
 */
@RunWith(AndroidJUnit4::class)
class MigrationBackupRoundTripTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), WalletDatabase::class.java)

    private var opened: WalletDatabase? = null

    @After
    fun closeDatabase() {
        opened?.close()
    }

    /** The fixture written as a schema-2 file, then opened by Room, which runs the real upgrade. */
    private fun upgradedFixtureWallet(): WalletDatabase {
        helper.createDatabase(DB_NAME, 2).use { LegacySchema2.insertFixture(it) }
        return Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            WalletDatabase::class.java,
            DB_NAME,
        ).addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build().also { opened = it }
    }

    private fun repositoryOn(database: WalletDatabase) = RoomBackupRepository(
        database, database.accountDao(), database.entryDao(), database.categoryDao(),
        database.currencyDao(), database.rateDao(), database.settingsDao(),
        database.categoryRoleDao(), database.legacyRowDao(), database.debtDao(), database.deductionDao(),
    )

    /** Children before parents, so no key is violated on the way. */
    private fun wipe(sqlite: SupportSQLiteDatabase) {
        listOf(
            "legacy_unreadable_rows", "category_roles", "entries", "deduction_items", "debts",
            "settings", "rates", "accounts", "categories", "currencies",
        ).forEach { sqlite.execSQL("DELETE FROM $it") }
    }

    /** Every table row by row as text. Leg ids are given out by the database, so legs are read without them. */
    private fun snapshot(sqlite: SupportSQLiteDatabase): List<List<List<String?>>> = SNAPSHOT_SQL.map { sqlite.textRows(it) }

    @Test
    fun anUpgradedFixtureWalletExportsAsFormatTwoAndRestoresToTheSameDatabaseAfterAWipe() = runTest {
        val database = upgradedFixtureWallet()
        val sqlite = database.openHelper.writableDatabase
        val repository = repositoryOn(database)
        val before = snapshot(sqlite)
        assertEquals("the upgrade made one header per old row", LegacyFixture.cases.size.toString(), sqlite.scalar("SELECT COUNT(*) FROM entries"))

        val exported = repository.exportBackup()
        wipe(sqlite)
        assertEquals("the wipe emptied the wallet", "0", sqlite.scalar("SELECT COUNT(*) FROM entries"))
        repository.restoreBackup(exported)

        assertEquals(2, BackupCodec.decodeV2(exported).version)
        assertEquals(before, snapshot(sqlite))
    }

    @Test
    fun theRestoredWalletKeepsRowsOnTheOtherKindOfCategoryAndOnAMissingCategoryWithTheirLegsAndNoRawCopy() = runTest {
        val database = upgradedFixtureWallet()
        val sqlite = database.openHelper.writableDatabase
        val repository = repositoryOn(database)

        val exported = repository.exportBackup()
        wipe(sqlite)
        repository.restoreBackup(exported)

        LegacyFixture.categoryMismatchRowIds.forEach { id ->
            val case = LegacyFixture.cases.single { it.row.id == id }
            assertEquals(
                "legs of row $id",
                case.expectedLegs.sortedBy { it.role }.map {
                    listOf(
                        it.role, it.position.toString(), it.accountId?.toString(), it.categoryId?.toString(),
                        it.subCategoryId?.toString(), it.amount, it.currencyCode, it.rateToBase,
                    )
                },
                sqlite.textRows(
                    "SELECT role, position, accountId, categoryId, subCategoryId, amount, currencyCode, rateToBase " +
                        "FROM entry_legs WHERE entryId = ? ORDER BY role",
                    id,
                ),
            )
            assertEquals("row $id keeps no raw copy", "0", sqlite.scalar("SELECT COUNT(*) FROM legacy_unreadable_rows WHERE id = ?", id))
        }
        assertEquals(
            "the raw copies of the unreadable and ignored-column rows came back",
            LegacyFixture.cases.count { it.reason != null }.toString(),
            sqlite.scalar("SELECT COUNT(*) FROM legacy_unreadable_rows"),
        )
        assertEquals(
            "the five roles came back on the same categories",
            LegacyFixture.expectedRoles.mapValues { it.value.toString() },
            sqlite.textRows("SELECT role, categoryId FROM category_roles").associate { it[0] to it[1] },
        )
    }

    @Test
    fun aSecondExportOfTheRestoredWalletIsTheSameBackupAsTheFirst() = runTest {
        val database = upgradedFixtureWallet()
        val sqlite = database.openHelper.writableDatabase
        val repository = repositoryOn(database)
        val first = BackupCodec.decodeV2(repository.exportBackup())

        wipe(sqlite)
        repository.restoreBackup(BackupCodec.encode(first))
        val second = BackupCodec.decodeV2(repository.exportBackup())

        assertTrue("the second backup is not empty", second.entries.isNotEmpty())
        assertEquals(first.entries.toSet(), second.entries.toSet())
        assertEquals(first.legs.toSet(), second.legs.toSet())
        assertEquals(first.categories.toSet(), second.categories.toSet())
        assertEquals(first.categoryRoles.toSet(), second.categoryRoles.toSet())
        assertEquals(first.legacyRows.toSet(), second.legacyRows.toSet())
        assertEquals(first.accounts.toSet(), second.accounts.toSet())
        assertEquals(first.settings, second.settings)
    }

    private companion object {
        const val DB_NAME = "migration-backup-round-trip-test.db"

        val SNAPSHOT_SQL = listOf(
            "SELECT * FROM settings ORDER BY id",
            "SELECT * FROM currencies ORDER BY code",
            "SELECT * FROM rates ORDER BY currencyCode",
            "SELECT * FROM accounts ORDER BY id",
            "SELECT * FROM categories ORDER BY id",
            "SELECT * FROM entries ORDER BY id",
            "SELECT entryId, role, position, accountId, debtId, categoryId, subCategoryId, amount, currencyCode, rateToBase " +
                "FROM entry_legs ORDER BY entryId, role, position",
            "SELECT * FROM category_roles ORDER BY role",
            "SELECT * FROM legacy_unreadable_rows ORDER BY id",
            "SELECT * FROM debts ORDER BY id",
            "SELECT * FROM deduction_items ORDER BY id",
            "SELECT * FROM income_deduction_lines ORDER BY id",
        )
    }
}
