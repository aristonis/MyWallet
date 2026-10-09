package org.aristonis.mywallet.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.data.fixture.LegacyFixture
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The upgrade deletes the old `transactions` table, so before it does it proves that every row can be
 * rebuilt, to the exact text, from the new tables. This is the only thing standing between a wrong
 * copy and a lost record, so it is proven here by tampering: each change below must make the check
 * throw, and an untouched copy must not.
 *
 * Assumed signature, which the implementation must match: a top-level function in package
 * `org.aristonis.mywallet.data.db` (file LegacyIdentityCheck.kt)
 *
 *     fun assertLegacyIdentity(db: SupportSQLiteDatabase)
 *
 * It reads the old `transactions` table and the new `entries`, `entry_legs` and
 * `legacy_unreadable_rows` tables of the same database, and throws on any difference.
 *
 * The check needs both generations of tables at once, which only exists in the middle of the real
 * upgrade. These tests get the same state by running the whole upgrade on the shared fixture and
 * then putting the old table back with the fixture's own rows.
 */
@RunWith(AndroidJUnit4::class)
class MigrationIdentityCheckTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), WalletDatabase::class.java)

    private fun migratedWithOldTableRestored(): SupportSQLiteDatabase {
        helper.createDatabase(DB_NAME, 2).use { LegacySchema2.insertFixture(it) }
        val db = helper.runMigrationsAndValidate(DB_NAME, 3, true, *ALL_MIGRATIONS)
        LegacySchema2.createTransactionsTable(db)
        LegacySchema2.insertTransactions(db, LegacyFixture.cases.map { it.row })
        return db
    }

    private fun assertThrowsOnTamper(description: String, tamper: (SupportSQLiteDatabase) -> Unit) {
        val db = migratedWithOldTableRestored()
        assertLegacyIdentity(db) // the copy is sound until it is touched
        tamper(db)

        var thrown: Throwable? = null
        try {
            assertLegacyIdentity(db)
        } catch (e: Exception) {
            thrown = e
        }

        assertNotNull("$description must fail the identity check", thrown)
    }

    @Test
    fun anUntouchedCopyPasses() {
        assertLegacyIdentity(migratedWithOldTableRestored())
    }

    @Test
    fun aLegAmountChangedByOneCharacterFails() = assertThrowsOnTamper("a leg amount changed by one character") {
        it.execSQL("UPDATE entry_legs SET amount = '2500.01' WHERE entryId = 1 AND role = 'ACCOUNT'")
    }

    @Test
    fun aTrailingZeroOnALegAmountFails() = assertThrowsOnTamper("45.50 where 45.5 was stored") {
        it.execSQL("UPDATE entry_legs SET amount = '-45.50' WHERE entryId = 2 AND role = 'ACCOUNT'")
    }

    @Test
    fun aDeletedRawCopyFails() = assertThrowsOnTamper("an unreadable entry without its raw copy") {
        it.execSQL("DELETE FROM legacy_unreadable_rows WHERE id = 14")
    }

    @Test
    fun aRawCopyThatDiffersFromItsSourceRowFails() = assertThrowsOnTamper("an edited raw copy") {
        it.execSQL("UPDATE legacy_unreadable_rows SET note = 'edited' WHERE id = 14")
    }

    @Test
    fun anExtraLegFails() = assertThrowsOnTamper("a third leg on an income") {
        it.execSQL(
            "INSERT INTO entry_legs (entryId, role, position, accountId, amount, currencyCode) " +
                "VALUES (1, 'FEE', 0, 1, '1.00', 'USD')",
        )
    }

    @Test
    fun aLegOnAnEntryThatShouldHaveNoneFails() = assertThrowsOnTamper("a leg on an unreadable entry") {
        it.execSQL(
            "INSERT INTO entry_legs (entryId, role, position, accountId, amount, currencyCode) " +
                "VALUES (14, 'ACCOUNT', 0, 1, '10.00', 'USD')",
        )
    }

    @Test
    fun aLegOnTheWrongAccountFails() = assertThrowsOnTamper("an account leg moved to another account") {
        it.execSQL("UPDATE entry_legs SET accountId = 5 WHERE entryId = 1 AND role = 'ACCOUNT'")
    }

    @Test
    fun aChangedMainLegAmountFails() = assertThrowsOnTamper("a category leg amount that differs from the row") {
        it.execSQL("UPDATE entry_legs SET amount = '2500.5' WHERE entryId = 1 AND role = 'MAIN'")
    }

    @Test
    fun aChangedMainLegRateFails() = assertThrowsOnTamper("a category leg rate that is not the saved rate") {
        it.execSQL("UPDATE entry_legs SET rateToBase = '1.09' WHERE entryId = 3 AND role = 'MAIN'")
    }

    @Test
    fun aMissingMainLegRateFails() = assertThrowsOnTamper("a category leg that lost its rate") {
        it.execSQL("UPDATE entry_legs SET rateToBase = NULL WHERE entryId = 3 AND role = 'MAIN'")
    }

    @Test
    fun aChangedHeaderPairRateFails() = assertThrowsOnTamper("a transfer header with another pair rate") {
        it.execSQL("UPDATE entries SET pairRate = '0.926' WHERE id = 8")
    }

    @Test
    fun aMissingHeaderFails() = assertThrowsOnTamper("a row without a header") {
        it.execSQL("DELETE FROM entries WHERE id = 13")
    }

    private companion object {
        const val DB_NAME = "migration-identity-test.db"
    }
}
