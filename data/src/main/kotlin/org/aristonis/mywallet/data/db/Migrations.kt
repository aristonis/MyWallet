package org.aristonis.mywallet.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 2 adds the index that lets a date range be answered without reading the whole table.
 * It only adds an index, so every existing row is carried over as it is.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_date` ON `transactions` (`date`)")
    }
}

/** Every migration, oldest first. A new schema version adds its step here. */
val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)
