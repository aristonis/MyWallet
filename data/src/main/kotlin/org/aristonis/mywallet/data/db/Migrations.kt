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

/** Every migration, oldest first. A new schema version adds its step here. */
val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)
