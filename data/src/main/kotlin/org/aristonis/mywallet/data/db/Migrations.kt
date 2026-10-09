package org.aristonis.mywallet.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1 is the shape of the first release; version 2 is the next release's, and everything that
 * release changes in the schema goes into this one step rather than into versions no device will
 * ever have. It adds the index that lets a date range be answered without reading the whole table,
 * and the switch for keeping rates typed on a transaction form, on by default so an existing install
 * behaves as it did before. Both only add, so every existing row is carried over as it is.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_date` ON `transactions` (`date`)")
        db.execSQL("ALTER TABLE `settings` ADD COLUMN `saveTransactionRates` INTEGER NOT NULL DEFAULT 1")
    }
}

/**
 * Version 3 stores each entry as a header in `entries` plus signed legs in `entry_legs`, replacing
 * the single `transactions` table, and adds the debt, deduction and role tables.
 *
 * The owner's records exist nowhere else, so the copy is held to an exact standard before the old
 * table goes:
 * - it is plain SQL, and every amount and rate is copied as text, so "1.50" stays "1.50";
 * - a row whose shape 1.1.1 never wrote (an unknown type, an income or expense with no category, a
 *   transfer missing its destination) keeps a raw copy with the reason, and its header is marked
 *   unreadable with no legs, so nothing is summed from it and nothing is lost;
 * - [assertLegacyIdentity] rebuilds every old row from the new tables and compares it to the
 *   original, and only then is `transactions` dropped;
 * - the foreign key check runs last, because Room does not run it after a hand-written migration.
 *
 * Room runs all of it in one transaction and then checks the result against the exported 3.json in
 * the same transaction, so any failure leaves the file exactly as it was, at version 2.
 *
 * The CREATE statements are copied from the exported 3.json, never written by hand, so a fresh
 * install and an upgraded one end up with the same tables. Every other literal below (type names,
 * roles, reasons, category names) is frozen with this version: it is not read from the app's
 * current code, which may change while the databases this upgrades stay as they are.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        SCHEMA_3_NEW_TABLES.forEach(db::execSQL)
        // Appended in this order with no default, matching how AccountEntity declares them.
        db.execSQL("ALTER TABLE `accounts` ADD COLUMN `creditLimit` TEXT")
        db.execSQL("ALTER TABLE `accounts` ADD COLUMN `defaultFeeMode` TEXT")
        db.execSQL("ALTER TABLE `accounts` ADD COLUMN `defaultFeeValue` TEXT")

        db.execSQL(COPY_ROWS_KEPT_RAW)
        db.execSQL(COPY_HEADERS)
        db.execSQL(COPY_ACCOUNT_LEGS)
        db.execSQL(COPY_CATEGORY_LEGS)
        db.execSQL(COPY_DESTINATION_LEGS)

        db.execSQL(ADD_PRE_MADE_CATEGORIES)
        PRE_MADE_ROLES.forEach { (role, kind, name) ->
            db.execSQL(
                "INSERT INTO category_roles (role, categoryId) " +
                    "SELECT ?, id FROM categories WHERE kind = ? AND parentId = 0 AND name = ?",
                arrayOf<Any?>(role, kind, name),
            )
        }

        // Ids are never reused: an undo may still hold a deleted row's id. Delete and insert rather
        // than update, so an emptied table that still has a sequence carries it too.
        db.execSQL("DELETE FROM sqlite_sequence WHERE name = 'entries'")
        db.execSQL(
            "INSERT INTO sqlite_sequence (name, seq) SELECT 'entries', seq FROM sqlite_sequence WHERE name = 'transactions'",
        )

        assertLegacyIdentity(db)
        db.execSQL("DROP TABLE transactions")
        // Foreign keys are off while a migration runs, and the check still works then. It runs after
        // the drop, so only the version 3 tables are checked.
        assertNoDanglingKeys(db)
    }
}

/** Every migration, oldest first. A new schema version adds its step here. */
val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

/** The version 3 tables and indexes, copied verbatim from the exported 3.json. */
private val SCHEMA_3_NEW_TABLES = listOf(
    "CREATE TABLE IF NOT EXISTS `entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `date` INTEGER NOT NULL, `note` TEXT, `pairRate` TEXT)",
    "CREATE INDEX IF NOT EXISTS `index_entries_date` ON `entries` (`date`)",
    "CREATE TABLE IF NOT EXISTS `entry_legs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `entryId` INTEGER NOT NULL, `role` TEXT NOT NULL, `position` INTEGER NOT NULL, `accountId` INTEGER, `debtId` INTEGER, `categoryId` INTEGER, `subCategoryId` INTEGER, `amount` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, `rateToBase` TEXT, FOREIGN KEY(`entryId`) REFERENCES `entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`debtId`) REFERENCES `debts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`subCategoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_entry_legs_entryId_role_position` ON `entry_legs` (`entryId`, `role`, `position`)",
    "CREATE INDEX IF NOT EXISTS `index_entry_legs_entryId_position` ON `entry_legs` (`entryId`, `position`)",
    "CREATE INDEX IF NOT EXISTS `index_entry_legs_accountId_amount` ON `entry_legs` (`accountId`, `amount`)",
    "CREATE INDEX IF NOT EXISTS `index_entry_legs_debtId_amount` ON `entry_legs` (`debtId`, `amount`)",
    "CREATE INDEX IF NOT EXISTS `index_entry_legs_categoryId` ON `entry_legs` (`categoryId`)",
    "CREATE INDEX IF NOT EXISTS `index_entry_legs_subCategoryId` ON `entry_legs` (`subCategoryId`)",
    "CREATE TABLE IF NOT EXISTS `debts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `counterparty` TEXT NOT NULL, `counterpartyKey` TEXT NOT NULL, `side` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, `dueDate` INTEGER, `note` TEXT)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_debts_side_currencyCode_counterpartyKey` ON `debts` (`side`, `currencyCode`, `counterpartyKey`)",
    "CREATE TABLE IF NOT EXISTS `deduction_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `nameKey` TEXT NOT NULL, `mode` TEXT NOT NULL, `value` TEXT NOT NULL, `valueCurrencyCode` TEXT, `timing` TEXT NOT NULL, `expenseCategoryId` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_deduction_items_nameKey` ON `deduction_items` (`nameKey`)",
    "CREATE TABLE IF NOT EXISTS `deduction_item_auto_categories` (`itemId` INTEGER NOT NULL, `incomeCategoryId` INTEGER NOT NULL, PRIMARY KEY(`itemId`, `incomeCategoryId`), FOREIGN KEY(`itemId`) REFERENCES `deduction_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`incomeCategoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_deduction_item_auto_categories_incomeCategoryId` ON `deduction_item_auto_categories` (`incomeCategoryId`)",
    "CREATE TABLE IF NOT EXISTS `income_deduction_lines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `entryId` INTEGER NOT NULL, `itemId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `name` TEXT NOT NULL, `mode` TEXT NOT NULL, `value` TEXT NOT NULL, `valueCurrencyCode` TEXT, `timing` TEXT NOT NULL, FOREIGN KEY(`entryId`) REFERENCES `entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`itemId`) REFERENCES `deduction_items`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_income_deduction_lines_entryId_position` ON `income_deduction_lines` (`entryId`, `position`)",
    "CREATE INDEX IF NOT EXISTS `index_income_deduction_lines_itemId` ON `income_deduction_lines` (`itemId`)",
    "CREATE TABLE IF NOT EXISTS `category_roles` (`role` TEXT NOT NULL, `categoryId` INTEGER NOT NULL, PRIMARY KEY(`role`), FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_category_roles_categoryId` ON `category_roles` (`categoryId`)",
    "CREATE TABLE IF NOT EXISTS `legacy_unreadable_rows` (`id` INTEGER NOT NULL, `type` TEXT NOT NULL, `date` INTEGER NOT NULL, `note` TEXT, `primaryAccountId` INTEGER NOT NULL, `primaryAmount` TEXT NOT NULL, `primaryCurrency` TEXT NOT NULL, `categoryId` INTEGER, `subCategoryId` INTEGER, `secondaryAccountId` INTEGER, `secondaryAmount` TEXT, `secondaryCurrency` TEXT, `rateUsed` TEXT, `reason` TEXT NOT NULL, PRIMARY KEY(`id`))",
)

/**
 * A raw copy of every row without the exact shape 1.1.1 writes: an income or expense with a
 * category and no secondary columns, or a transfer with all four secondary columns and no category
 * columns. The CASE order decides the reason of a row with two faults.
 */
private const val COPY_ROWS_KEPT_RAW =
    "INSERT INTO legacy_unreadable_rows " +
        "SELECT id, type, date, note, primaryAccountId, primaryAmount, primaryCurrency, categoryId, subCategoryId, " +
        "secondaryAccountId, secondaryAmount, secondaryCurrency, rateUsed, " +
        "CASE " +
        "WHEN type NOT IN ('INCOME', 'EXPENSE', 'TRANSFER') THEN 'UNKNOWN_TYPE' " +
        "WHEN type <> 'TRANSFER' AND categoryId IS NULL THEN 'MISSING_CATEGORY' " +
        "WHEN type = 'TRANSFER' AND (secondaryAccountId IS NULL OR secondaryAmount IS NULL " +
        "OR secondaryCurrency IS NULL OR rateUsed IS NULL) THEN 'MISSING_DESTINATION' " +
        "ELSE 'IGNORED_COLUMNS' " +
        "END " +
        "FROM transactions " +
        "WHERE type NOT IN ('INCOME', 'EXPENSE', 'TRANSFER') " +
        "OR (type <> 'TRANSFER' AND (categoryId IS NULL OR secondaryAccountId IS NOT NULL " +
        "OR secondaryAmount IS NOT NULL OR secondaryCurrency IS NOT NULL OR rateUsed IS NOT NULL)) " +
        "OR (type = 'TRANSFER' AND (secondaryAccountId IS NULL OR secondaryAmount IS NULL " +
        "OR secondaryCurrency IS NULL OR rateUsed IS NULL OR categoryId IS NOT NULL OR subCategoryId IS NOT NULL))"

/**
 * One header per row, ids kept. A row 1.1.1 could not read becomes LEGACY_UNREADABLE; one it read
 * while ignoring stray columns keeps its type. A transfer's rate becomes the header's pair rate.
 */
private const val COPY_HEADERS =
    "INSERT INTO entries (id, kind, date, note, pairRate) " +
        "SELECT t.id, " +
        "CASE WHEN l.reason IN ('UNKNOWN_TYPE', 'MISSING_CATEGORY', 'MISSING_DESTINATION') " +
        "THEN 'LEGACY_UNREADABLE' ELSE t.type END, " +
        "t.date, t.note, " +
        "CASE WHEN t.type = 'TRANSFER' AND l.reason IS NOT 'MISSING_DESTINATION' THEN t.rateUsed END " +
        "FROM transactions t LEFT JOIN legacy_unreadable_rows l ON l.id = t.id ORDER BY t.id"

/** The account leg (SOURCE for a transfer): money out gets one leading "-" as text, never through arithmetic. */
private const val COPY_ACCOUNT_LEGS =
    "INSERT INTO entry_legs (entryId, role, position, accountId, amount, currencyCode) " +
        "SELECT t.id, " +
        "CASE WHEN e.kind = 'TRANSFER' THEN 'SOURCE' ELSE 'ACCOUNT' END, " +
        "0, t.primaryAccountId, " +
        "CASE WHEN e.kind = 'INCOME' THEN t.primaryAmount ELSE '-' || t.primaryAmount END, " +
        "t.primaryCurrency " +
        "FROM transactions t JOIN entries e ON e.id = t.id " +
        "WHERE e.kind <> 'LEGACY_UNREADABLE' ORDER BY t.id"

/**
 * The MAIN category leg of an income or expense, stamped with the rate saved today: "1" in the base
 * currency (even when the rates table holds a row for it), the saved rate otherwise, none when
 * there is no saved rate.
 */
private const val COPY_CATEGORY_LEGS =
    "INSERT INTO entry_legs (entryId, role, position, categoryId, subCategoryId, amount, currencyCode, rateToBase) " +
        "SELECT t.id, 'MAIN', 0, t.categoryId, t.subCategoryId, t.primaryAmount, t.primaryCurrency, " +
        "CASE WHEN t.primaryCurrency = (SELECT baseCurrencyCode FROM settings WHERE id = 0) THEN '1' " +
        "ELSE (SELECT rateToBase FROM rates r WHERE r.currencyCode = t.primaryCurrency) END " +
        "FROM transactions t JOIN entries e ON e.id = t.id " +
        "WHERE e.kind IN ('INCOME', 'EXPENSE') ORDER BY t.id"

private const val COPY_DESTINATION_LEGS =
    "INSERT INTO entry_legs (entryId, role, position, accountId, amount, currencyCode) " +
        "SELECT t.id, 'DEST', 0, t.secondaryAccountId, t.secondaryAmount, t.secondaryCurrency " +
        "FROM transactions t JOIN entries e ON e.id = t.id " +
        "WHERE e.kind = 'TRANSFER' ORDER BY t.id"

/**
 * One statement, in this order, so the new rows get the same ids on every device. The unique
 * (kind, parentId, name NOCASE) index makes an existing top-level category of the same name and kind
 * win, whatever its case; an ignored row still uses up its id.
 */
private const val ADD_PRE_MADE_CATEGORIES =
    "INSERT OR IGNORE INTO categories (name, kind, parentId) VALUES " +
        "('Interest', 'INCOME', 0), ('Interest', 'EXPENSE', 0), ('Tax', 'EXPENSE', 0), " +
        "('Fees', 'EXPENSE', 0), ('Lost debt', 'EXPENSE', 0), ('Debt forgiven', 'INCOME', 0)"

/** Each role with the kind and name of the top-level category it files under. */
private val PRE_MADE_ROLES = listOf(
    Triple("INTEREST_IN", "INCOME", "Interest"),
    Triple("INTEREST_OUT", "EXPENSE", "Interest"),
    Triple("FEES", "EXPENSE", "Fees"),
    Triple("LOST_DEBT", "EXPENSE", "Lost debt"),
    Triple("DEBT_FORGIVEN", "INCOME", "Debt forgiven"),
)

/**
 * Throws when any foreign key in the database points at a row that does not exist. Room runs this
 * check only after its own automatic migrations, so a hand-written one has to run it itself.
 */
private fun assertNoDanglingKeys(db: SupportSQLiteDatabase) {
    db.query("PRAGMA foreign_key_check").use { cursor ->
        check(!cursor.moveToFirst()) {
            "the upgrade left a key pointing at nothing: table ${cursor.getString(0)}, row ${cursor.getLong(1)}, " +
                "parent ${cursor.getString(2)}"
        }
    }
}
