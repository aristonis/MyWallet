package org.aristonis.mywallet.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The Room database — all six tables and their DAOs.
 *
 * Version 1 is installed on a device holding real records, so the schema is no longer edited in
 * place. Every change bumps the version and adds a migration in Migrations.kt that carries the
 * existing rows forward, with a MigrationTestHelper test that starts from the previous version's
 * exported schema.
 *
 * `exportSchema = true` is what makes that possible: the JSON under `data/schemas/` is the only
 * record of what a version's tables were, and a migration can neither be written nor tested
 * without the shape it starts from.
 */
@Database(
    entities = [
        AccountEntity::class,
        TransactionEntity::class,
        CategoryEntity::class,
        CurrencyEntity::class,
        RateEntity::class,
        SettingsEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class WalletDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun currencyDao(): CurrencyDao
    abstract fun rateDao(): RateDao
    abstract fun settingsDao(): SettingsDao
}
