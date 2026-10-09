package org.aristonis.mywallet.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.data.fixture.LegacyFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal

/**
 * Version 1 is installed on a phone holding real records, and the data exists nowhere else. Every
 * upgrade has to carry those rows across untouched, so each migration is proven here against a
 * database built from the exported schema of the version it starts from.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), WalletDatabase::class.java)

    @Test
    fun version1To2KeepsEveryRowAddsTheDateIndexAndTurnsRateSavingOn() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.execSQL("INSERT INTO settings (id, baseCurrencyCode, theme, schemaVersion) VALUES (0, 'SYP', 'DARK', 1)")
            db.execSQL(
                "INSERT INTO transactions (id, type, date, primaryAccountId, primaryAmount, primaryCurrency, categoryId) " +
                    "VALUES (1, 'EXPENSE', 20675, 1, '12.50', 'USD', 3), (2, 'INCOME', 20680, 1, '900', 'USD', 4)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(DB_NAME, 2, true, *ALL_MIGRATIONS)

        migrated.query("SELECT id, primaryAmount FROM transactions ORDER BY id").use { cursor ->
            assertEquals(2, cursor.count)
            cursor.moveToFirst()
            assertEquals("12.50", cursor.getString(1))
        }
        migrated.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_transactions_date'").use {
            assertTrue(it.moveToFirst())
        }
        migrated.query("SELECT baseCurrencyCode, theme, saveTransactionRates FROM settings").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("SYP", cursor.getString(0))
            assertEquals("DARK", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
        }
    }

    // --- Version 2 to 3: the storage switch. Every start state is the shared schema-2 fixture. ---

    @Test
    fun version2To3RebuildsEveryFixtureRowFromHeadersLegsAndRawCopies() {
        val migrated = migrateFixture()

        assertEquals(3, migrated.version)
        LegacyFixture.cases.forEach { case ->
            val row = case.row
            assertEquals(
                "header of row ${row.id}",
                listOf(listOf(case.expectedKind, row.date.toString(), row.note, case.expectedPairRate)),
                migrated.textRows("SELECT kind, date, note, pairRate FROM entries WHERE id = ?", row.id),
            )
            assertEquals(
                "legs of row ${row.id}",
                case.expectedLegs.sortedWith(compareBy({ it.role }, { it.position })).map(::legText),
                migrated.textRows(
                    "SELECT role, position, accountId, categoryId, subCategoryId, amount, currencyCode, rateToBase " +
                        "FROM entry_legs WHERE entryId = ? ORDER BY role, position",
                    row.id,
                ),
            )
            assertEquals(
                "raw copy of row ${row.id}",
                listOfNotNull(case.reason?.let { rawCopyText(row, it) }),
                migrated.textRows(RAW_COPY_SQL + " WHERE id = ?", row.id),
            )
        }
        assertEquals(LegacyFixture.cases.size.toString(), migrated.scalar("SELECT COUNT(*) FROM entries"))
        assertEquals(
            LegacyFixture.cases.sumOf { it.expectedLegs.size }.toString(),
            migrated.scalar("SELECT COUNT(*) FROM entry_legs"),
        )
        assertEquals(
            LegacyFixture.cases.count { it.reason != null }.toString(),
            migrated.scalar("SELECT COUNT(*) FROM legacy_unreadable_rows"),
        )
        assertEquals(
            "the old table is gone",
            null,
            migrated.scalar("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'transactions'"),
        )
    }

    @Test
    fun version2To3TurnsRowsTheOldAppCouldNotReadIntoLegFreeUnreadableHeadersWithAReasonedRawCopy() {
        val migrated = migrateFixture()

        LegacyFixture.REASONS.forEach { reason ->
            val unreadable = LegacyFixture.cases.filter { it.reason == reason }
            assertTrue("the fixture has a row for $reason", unreadable.isNotEmpty())
            unreadable.forEach { case ->
                val id = case.row.id
                val headerKind = migrated.scalar("SELECT kind FROM entries WHERE id = ?", id)
                if (reason == LegacyFixture.IGNORED_COLUMNS) {
                    // A row 1.1.1 did read keeps its kind and its legs; only the raw copy is extra.
                    assertEquals("row $id", case.row.type, headerKind)
                    assertEquals("row $id", "2", migrated.scalar("SELECT COUNT(*) FROM entry_legs WHERE entryId = ?", id))
                } else {
                    assertEquals("row $id", LegacyFixture.KIND_UNREADABLE, headerKind)
                    assertEquals("row $id", "0", migrated.scalar("SELECT COUNT(*) FROM entry_legs WHERE entryId = ?", id))
                }
                assertEquals("row $id", reason, migrated.scalar("SELECT reason FROM legacy_unreadable_rows WHERE id = ?", id))
            }
        }
    }

    @Test
    fun version2To3KeepsEveryAccountBalanceTheSame() {
        val migrated = migrateFixture()

        val before = balancesFromOldRows()
        val after = balancesFromLegs(migrated)

        assertEquals(before.keys, after.keys)
        before.forEach { (accountId, balance) ->
            assertEquals("account $accountId", 0, balance.compareTo(after.getValue(accountId)))
        }
    }

    @Test
    fun version2To3AddsThePreMadeCategoriesOnceAndReusesANameTheOwnerAlreadyHas() {
        val migrated = migrateFixture()

        assertEquals(LegacyFixture.expectedCategoryCountAfter.toString(), migrated.scalar("SELECT COUNT(*) FROM categories"))
        assertEquals(
            "the owner's own Tax is the only Tax",
            "1",
            migrated.scalar("SELECT COUNT(*) FROM categories WHERE name = 'Tax' AND kind = 'EXPENSE' AND parentId = 0"),
        )
        assertEquals(
            "the owner's lowercase fees is the only Fees and keeps its spelling",
            listOf(listOf("7", "fees")),
            migrated.textRows("SELECT id, name FROM categories WHERE name = 'Fees' AND kind = 'EXPENSE' AND parentId = 0"),
        )
        assertEquals(
            listOf(
                listOf("10", "Interest", "INCOME"),
                listOf("11", "Interest", "EXPENSE"),
                listOf("14", "Lost debt", "EXPENSE"),
                listOf("15", "Debt forgiven", "INCOME"),
            ),
            migrated.textRows("SELECT id, name, kind FROM categories WHERE id > 9 ORDER BY id"),
        )
        assertEquals(
            LegacyFixture.expectedRoles.mapValues { it.value.toString() },
            migrated.textRows("SELECT role, categoryId FROM category_roles").associate { it[0] to it[1] },
        )
    }

    @Test
    fun version2To3GivesTheNextNewEntryAnIdAboveEveryIdTheOldTableEverUsed() {
        val migrated = migrateFixture()

        assertEquals(LegacyFixture.sequenceBefore.toString(), migrated.scalar(ENTRIES_SEQUENCE_SQL))
        migrated.execSQL("INSERT INTO entries (kind, date) VALUES ('INCOME', 20454)")

        assertEquals((LegacyFixture.sequenceBefore + 1).toString(), migrated.scalar("SELECT MAX(id) FROM entries"))
    }

    @Test
    fun version2To3StoresTheCategoryLegRateAsTheSavedRateOneInTheBaseCurrencyAndNothingWhenThereIsNone() {
        val migrated = migrateFixture()

        fun mainRate(entryId: Long) =
            migrated.scalar("SELECT rateToBase FROM entry_legs WHERE entryId = ? AND role = 'MAIN'", entryId)

        assertEquals("a base-currency row stores 1 even though the rates table holds 1.0 for USD", "1", mainRate(1))
        assertEquals("a rated currency stores the saved rate", "1.08", mainRate(3))
        assertEquals("a currency with no saved rate stores none", null, mainRate(7))
        assertEquals(
            "account legs never carry a rate",
            "0",
            migrated.scalar("SELECT COUNT(*) FROM entry_legs WHERE accountId IS NOT NULL AND rateToBase IS NOT NULL"),
        )
    }

    @Test
    fun version2To3OfAnEmptiedTableThatHasASequenceKeepsTheSequence() {
        // Rows 1..7 existed and were all deleted: the table is empty but its sequence is still 7, and
        // the next entry must not reuse an id an undo could still be holding.
        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL(
                "INSERT INTO transactions (id, type, date, primaryAccountId, primaryAmount, primaryCurrency, categoryId) " +
                    "VALUES (7, 'EXPENSE', 20454, 1, '1', 'USD', 3)",
            )
            db.execSQL("DELETE FROM transactions")
        }

        val migrated = helper.runMigrationsAndValidate(DB_NAME, 3, true, *ALL_MIGRATIONS)

        assertEquals("0", migrated.scalar("SELECT COUNT(*) FROM entries"))
        assertEquals("7", migrated.scalar(ENTRIES_SEQUENCE_SQL))
        migrated.execSQL("INSERT INTO entries (kind, date) VALUES ('INCOME', 20454)")
        assertEquals("8", migrated.scalar("SELECT MAX(id) FROM entries"))
    }

    @Test
    fun version2To3WithADanglingCategoryRoleFailsAtTheLastStepAndTheFileStaysAtVersion2() {
        // The last step of the upgrade is a foreign key check. A trigger on the old categories table
        // plants a category_roles row that points at no category the moment the upgrade adds its
        // "Lost debt" category, which is the only way to make that table dangle from a schema-2 file.
        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL("INSERT INTO categories (id, name, kind, parentId) VALUES (3, 'Food', 'EXPENSE', 0)")
            db.execSQL(
                "INSERT INTO transactions (id, type, date, primaryAccountId, primaryAmount, primaryCurrency, categoryId) " +
                    "VALUES (1, 'EXPENSE', 20454, 1, '12.50', 'USD', 3)",
            )
            db.execSQL(
                "CREATE TRIGGER plant_dangling_role AFTER INSERT ON categories WHEN NEW.name = 'Lost debt' " +
                    "BEGIN INSERT INTO category_roles (role, categoryId) VALUES ('GHOST_ROLE', 9999); END",
            )
        }

        var thrown: Throwable? = null
        try {
            helper.runMigrationsAndValidate(DB_NAME, 3, true, *ALL_MIGRATIONS)
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull("a dangling role must fail the upgrade", thrown)

        val untouched = helper.runMigrationsAndValidate(DB_NAME, 2, true)
        assertEquals(2, untouched.version)
        assertEquals(listOf(listOf("1", "12.50")), untouched.textRows("SELECT id, primaryAmount FROM transactions"))
        assertEquals(
            "nothing of schema 3 was left behind",
            null,
            untouched.scalar("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'entries'"),
        )
    }

    @Test
    fun version2To3KeepsRowsOnTheOtherKindOfCategoryAndOnAMissingCategoryWithTheirLegsAndNoRawCopy() {
        val migrated = migrateFixture()

        assertEquals(listOf(24L, 25L, 30L), LegacyFixture.categoryMismatchRowIds)
        LegacyFixture.categoryMismatchRowIds.forEach { id ->
            val case = LegacyFixture.cases.single { it.row.id == id }
            assertEquals("header of row $id", case.expectedKind, migrated.scalar("SELECT kind FROM entries WHERE id = ?", id))
            assertEquals(
                "legs of row $id",
                case.expectedLegs.sortedBy { it.role }.map(::legText),
                migrated.textRows(
                    "SELECT role, position, accountId, categoryId, subCategoryId, amount, currencyCode, rateToBase " +
                        "FROM entry_legs WHERE entryId = ? ORDER BY role",
                    id,
                ),
            )
            assertEquals("row $id keeps no raw copy", "0", migrated.scalar("SELECT COUNT(*) FROM legacy_unreadable_rows WHERE id = ?", id))
        }
        assertEquals(
            "the row names a category that does not exist and the upgrade does not make one up",
            "0",
            migrated.scalar("SELECT COUNT(*) FROM categories WHERE id = ?", LegacyFixture.DELETED_CATEGORY),
        )
    }

    @Test
    fun version2To3LeavesNoKeyPointingAtNothing() {
        val migrated = migrateFixture()

        assertEquals(emptyList<List<String?>>(), migrated.textRows("PRAGMA foreign_key_check"))
    }

    @Test
    fun version2To3LeavesExactlyFiveRolesEachOnAnExistingTopLevelCategoryOfTheRightKind() {
        val migrated = migrateFixture()

        assertEquals("5", migrated.scalar("SELECT COUNT(*) FROM category_roles"))
        assertRolesBindTopLevelCategoriesOfTheRightKind(migrated)
    }

    @Test
    fun version2To3BindsTheFeesRoleToTheOwnersLowerCaseFeesAndAddsNoSecondFees() {
        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL("INSERT INTO categories (id, name, kind, parentId) VALUES (1, 'fees', 'EXPENSE', 0)")
        }

        val migrated = helper.runMigrationsAndValidate(DB_NAME, 3, true, *ALL_MIGRATIONS)

        assertEquals(
            "one Fees in any letter case",
            listOf(listOf("1", "fees")),
            migrated.textRows("SELECT id, name FROM categories WHERE lower(name) = 'fees' AND kind = 'EXPENSE' AND parentId = 0"),
        )
        assertEquals("1", migrated.scalar("SELECT categoryId FROM category_roles WHERE role = 'FEES'"))
        assertEquals("5", migrated.scalar("SELECT COUNT(*) FROM category_roles"))
        assertRolesBindTopLevelCategoriesOfTheRightKind(migrated)
    }

    @Test
    fun version2To3DoesNotBindTheFeesRoleToAnIncomeCategoryThatHappensToBeCalledFees() {
        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL("INSERT INTO categories (id, name, kind, parentId) VALUES (1, 'FEES', 'INCOME', 0)")
        }

        val migrated = helper.runMigrationsAndValidate(DB_NAME, 3, true, *ALL_MIGRATIONS)

        assertEquals("the owner's income category is left alone", listOf(listOf("1", "FEES", "INCOME")), migrated.textRows("SELECT id, name, kind FROM categories WHERE id = 1"))
        assertEquals(
            "the role gets an expense category of its own",
            listOf(listOf("EXPENSE", "Fees")),
            migrated.textRows("SELECT c.kind, c.name FROM category_roles r JOIN categories c ON c.id = r.categoryId WHERE r.role = 'FEES'"),
        )
        assertRolesBindTopLevelCategoriesOfTheRightKind(migrated)
    }

    @Test
    fun version2To3WhoseIdentityCheckFailsInsideTheRealUpgradeThrowsAndLeavesTheFileAtVersion2WithItsOldTable() {
        // A trigger on the old categories table rewrites one copied leg the moment the upgrade adds
        // its "Interest" category, which is after the legs are copied and before the identity check.
        helper.createDatabase(DB_NAME, 2).use { db ->
            LegacySchema2.insertFixture(db)
            db.execSQL(
                "CREATE TRIGGER change_a_copied_leg AFTER INSERT ON categories WHEN NEW.name = 'Interest' " +
                    "BEGIN UPDATE entry_legs SET amount = '999.99' WHERE entryId = 1 AND role = 'ACCOUNT'; END",
            )
        }

        var thrown: Throwable? = null
        try {
            helper.runMigrationsAndValidate(DB_NAME, 3, true, *ALL_MIGRATIONS)
        } catch (e: Exception) {
            thrown = e
        }

        assertNotNull("a copy that differs from the old rows must fail the upgrade", thrown)
        assertTrue(
            "the failure is the identity check, not something else: $thrown",
            generateSequence(thrown) { it.cause }.any { it.message.orEmpty().contains("could not rebuild the old rows exactly") },
        )
        val untouched = helper.runMigrationsAndValidate(DB_NAME, 2, true)
        assertEquals(2, untouched.version)
        assertEquals(
            "every old row is still there with its text",
            LegacyFixture.cases.sortedBy { it.row.id }.map { listOf(it.row.id.toString(), it.row.primaryAmount) },
            untouched.textRows("SELECT id, primaryAmount FROM transactions ORDER BY id"),
        )
        assertEquals(
            "nothing of schema 3 was left behind",
            null,
            untouched.scalar("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'entries'"),
        )
    }

    private fun assertRolesBindTopLevelCategoriesOfTheRightKind(db: SupportSQLiteDatabase) {
        assertEquals(
            EXPECTED_ROLE_KINDS,
            db.textRows(
                "SELECT r.role, c.kind, c.parentId FROM category_roles r JOIN categories c ON c.id = r.categoryId ORDER BY r.role",
            ).associate { it[0] to (it[1] to it[2]) }.mapValues { (_, kindAndParent) ->
                assertEquals("a role sits on a top-level category", "0", kindAndParent.second)
                kindAndParent.first
            },
        )
    }

    private fun migrateFixture(): SupportSQLiteDatabase {
        helper.createDatabase(DB_NAME, 2).use { LegacySchema2.insertFixture(it) }
        return helper.runMigrationsAndValidate(DB_NAME, 3, true, *ALL_MIGRATIONS)
    }

    private fun legText(leg: LegacyFixture.ExpectedLeg): List<String?> = listOf(
        leg.role, leg.position.toString(), leg.accountId?.toString(), leg.categoryId?.toString(),
        leg.subCategoryId?.toString(), leg.amount, leg.currencyCode, leg.rateToBase,
    )

    private fun rawCopyText(row: LegacyFixture.Row, reason: String): List<String?> = listOf(
        row.id.toString(), row.type, row.date.toString(), row.note, row.primaryAccountId.toString(),
        row.primaryAmount, row.primaryCurrency, row.categoryId?.toString(), row.subCategoryId?.toString(),
        row.secondaryAccountId?.toString(), row.secondaryAmount, row.secondaryCurrency, row.rateUsed, reason,
    )

    /** What 1.1.1 made of the rows it could read: opening balance plus money in minus money out. */
    private fun balancesFromOldRows(): Map<Long, BigDecimal> {
        val balances = LegacyFixture.accounts.associate { it.id to BigDecimal(it.openingBalanceAmount) }.toMutableMap()
        LegacyFixture.cases.filter { it.expectedKind != LegacyFixture.KIND_UNREADABLE }.map { it.row }.forEach { row ->
            val amount = BigDecimal(row.primaryAmount)
            when (row.type) {
                LegacyFixture.INCOME -> balances.merge(row.primaryAccountId, amount, BigDecimal::add)
                LegacyFixture.EXPENSE -> balances.merge(row.primaryAccountId, amount.negate(), BigDecimal::add)
                else -> {
                    balances.merge(row.primaryAccountId, amount.negate(), BigDecimal::add)
                    balances.merge(requireNotNull(row.secondaryAccountId), BigDecimal(requireNotNull(row.secondaryAmount)), BigDecimal::add)
                }
            }
        }
        return balances
    }

    private fun balancesFromLegs(db: SupportSQLiteDatabase): Map<Long, BigDecimal> {
        val balances = LegacyFixture.accounts.associate { it.id to BigDecimal(it.openingBalanceAmount) }.toMutableMap()
        db.textRows("SELECT accountId, amount FROM entry_legs WHERE accountId IS NOT NULL").forEach { (account, amount) ->
            balances.merge(account!!.toLong(), BigDecimal(amount!!), BigDecimal::add)
        }
        return balances
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
        val EXPECTED_ROLE_KINDS = mapOf(
            "DEBT_FORGIVEN" to "INCOME",
            "FEES" to "EXPENSE",
            "INTEREST_IN" to "INCOME",
            "INTEREST_OUT" to "EXPENSE",
            "LOST_DEBT" to "EXPENSE",
        )
        const val ENTRIES_SEQUENCE_SQL = "SELECT seq FROM sqlite_sequence WHERE name = 'entries'"
        const val RAW_COPY_SQL = "SELECT id, type, date, note, primaryAccountId, primaryAmount, primaryCurrency, " +
            "categoryId, subCategoryId, secondaryAccountId, secondaryAmount, secondaryCurrency, rateUsed, reason " +
            "FROM legacy_unreadable_rows"
    }
}
