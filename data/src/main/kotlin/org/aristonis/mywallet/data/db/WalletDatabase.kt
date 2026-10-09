package org.aristonis.mywallet.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The Room database: every table and its DAOs.
 *
 * Entries are stored as a header ([EntryEntity]) plus signed legs ([EntryLegEntity]); the old
 * single `transactions` table went away in version 3, and [LegacyUnreadableRowEntity] keeps a raw
 * copy of any old row the upgrade could not carry over in full.
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
        CategoryEntity::class,
        CurrencyEntity::class,
        RateEntity::class,
        SettingsEntity::class,
        EntryEntity::class,
        EntryLegEntity::class,
        DebtEntity::class,
        DeductionItemEntity::class,
        DeductionItemAutoCategoryEntity::class,
        IncomeDeductionLineEntity::class,
        CategoryRoleEntity::class,
        LegacyUnreadableRowEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class WalletDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun entryDao(): EntryDao
    abstract fun ledgerQueryDao(): LedgerQueryDao
    abstract fun categoryDao(): CategoryDao
    abstract fun currencyDao(): CurrencyDao
    abstract fun rateDao(): RateDao
    abstract fun settingsDao(): SettingsDao
    abstract fun debtDao(): DebtDao
    abstract fun deductionDao(): DeductionDao
    abstract fun categoryRoleDao(): CategoryRoleDao
    abstract fun legacyRowDao(): LegacyRowDao
}
