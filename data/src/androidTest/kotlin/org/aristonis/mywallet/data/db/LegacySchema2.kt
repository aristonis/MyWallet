package org.aristonis.mywallet.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.aristonis.mywallet.data.fixture.LegacyFixture

/**
 * Fills a schema-2 database (the shape 1.1.1 ships) with the shared [LegacyFixture], and re-creates
 * the old `transactions` table on a migrated database, so the upgrade tests all start from the same
 * rows through plain SQL and never through the current entity classes.
 */
internal object LegacySchema2 {

    /** Settings, currencies, rates, accounts, categories and every fixture transaction, with the old id sequence. */
    fun insertFixture(db: SupportSQLiteDatabase) {
        insertReferenceData(db)
        insertTransactions(db, LegacyFixture.cases.map { it.row })
        db.execSQL(
            "UPDATE sqlite_sequence SET seq = ? WHERE name = 'transactions'",
            arrayOf<Any?>(LegacyFixture.sequenceBefore),
        )
    }

    fun insertReferenceData(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO settings (id, baseCurrencyCode, theme, schemaVersion) VALUES (0, ?, 'SYSTEM', 2)",
            arrayOf<Any?>(LegacyFixture.baseCurrencyCode),
        )
        val codes = LegacyFixture.accounts.map { it.currencyCode }.toSet() + LegacyFixture.baseCurrencyCode
        codes.forEach { db.execSQL("INSERT INTO currencies (code, symbol, decimalPlaces) VALUES (?, ?, 2)", arrayOf<Any?>(it, it)) }
        LegacyFixture.rates.forEach { (code, rate) ->
            db.execSQL("INSERT INTO rates (currencyCode, rateToBase) VALUES (?, ?)", arrayOf<Any?>(code, rate))
        }
        LegacyFixture.accounts.forEach {
            db.execSQL(
                "INSERT INTO accounts (id, name, typeKey, currencyCode, openingBalanceAmount, archived, sortOrder) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    it.id, it.name, it.typeKey, it.currencyCode, it.openingBalanceAmount,
                    if (it.archived) 1 else 0, it.sortOrder,
                ),
            )
        }
        LegacyFixture.categories.forEach {
            db.execSQL(
                "INSERT INTO categories (id, name, kind, parentId, systemKey) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>(it.id, it.name, it.kind, it.parentId, it.systemKey),
            )
        }
    }

    fun insertTransactions(db: SupportSQLiteDatabase, rows: List<LegacyFixture.Row>) {
        rows.forEach {
            db.execSQL(
                "INSERT INTO transactions (id, type, date, note, primaryAccountId, primaryAmount, primaryCurrency, " +
                    "categoryId, subCategoryId, secondaryAccountId, secondaryAmount, secondaryCurrency, rateUsed) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    it.id, it.type, it.date, it.note, it.primaryAccountId, it.primaryAmount, it.primaryCurrency,
                    it.categoryId, it.subCategoryId, it.secondaryAccountId, it.secondaryAmount,
                    it.secondaryCurrency, it.rateUsed,
                ),
            )
        }
    }

    /** The schema-2 `transactions` table exactly as the exported 2.json describes it. */
    fun createTransactionsTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`type` TEXT NOT NULL, `date` INTEGER NOT NULL, `note` TEXT, `primaryAccountId` INTEGER NOT NULL, " +
                "`primaryAmount` TEXT NOT NULL, `primaryCurrency` TEXT NOT NULL, `categoryId` INTEGER, " +
                "`subCategoryId` INTEGER, `secondaryAccountId` INTEGER, `secondaryAmount` TEXT, " +
                "`secondaryCurrency` TEXT, `rateUsed` TEXT, FOREIGN KEY(`subCategoryId`) " +
                "REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        )
    }
}

/**
 * Every value of every row as text (null stays null), in query order. Comparing as text is the point:
 * the upgrade copies amounts byte for byte, so "1.50" and "1.5" must not compare equal.
 */
internal fun SupportSQLiteDatabase.textRows(sql: String, vararg args: Any?): List<List<String?>> =
    query(sql, arrayOf(*args)).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
            }
        }
    }

internal fun SupportSQLiteDatabase.scalar(sql: String, vararg args: Any?): String? =
    textRows(sql, *args).singleOrNull()?.singleOrNull()
