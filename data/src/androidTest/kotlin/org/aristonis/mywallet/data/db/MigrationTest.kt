package org.aristonis.mywallet.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
