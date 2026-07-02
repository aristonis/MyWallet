package org.aristonis.mywallet.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The Room database. All six tables live here; DAOs are added as SG-4 proceeds. `version` bumps
 * drive migrations (pre-first-release we just rebuild). `exportSchema = false` for now.
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
    exportSchema = false,
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
