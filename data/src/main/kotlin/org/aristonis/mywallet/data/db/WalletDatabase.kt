package org.aristonis.mywallet.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The Room database — all six tables and their DAOs.
 *
 * The version stays at 1 and no `Migration` exists, because the app has never been released: no
 * database of an older shape is installed anywhere, so a schema change is made by editing the
 * entities and reinstalling. The first release ends that. From the build that ships, every
 * installed copy is somebody's only record of their money, and each further change needs a version
 * bump plus a migration that carries the existing rows forward.
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
    version = 1,
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
