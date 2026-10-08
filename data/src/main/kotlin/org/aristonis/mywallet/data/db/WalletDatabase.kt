package org.aristonis.mywallet.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The Room database — all six tables and their DAOs.
 *
 * A schema version is frozen once a release ships it: installed copies are somebody's only record
 * of their money, so that shape is never edited again. The next release's changes all go into one
 * new version with one migration in Migrations.kt that carries the existing rows forward, proven by
 * a MigrationTestHelper test that starts from the shipped version's exported schema. A version no
 * release has shipped yet is still open, so it is edited rather than stacked on.
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
