package org.aristonis.mywallet.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.aristonis.mywallet.data.db.DatabaseFactory
import org.aristonis.mywallet.data.db.WalletDatabase
import javax.inject.Singleton

/** Provides the Room database (built + seeded in :data) and its DAOs. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): WalletDatabase =
        DatabaseFactory.create(context)

    @Provides fun provideAccountDao(db: WalletDatabase) = db.accountDao()
    @Provides fun provideEntryDao(db: WalletDatabase) = db.entryDao()
    @Provides fun provideLedgerQueryDao(db: WalletDatabase) = db.ledgerQueryDao()
    @Provides fun provideCategoryDao(db: WalletDatabase) = db.categoryDao()
    @Provides fun provideCurrencyDao(db: WalletDatabase) = db.currencyDao()
    @Provides fun provideRateDao(db: WalletDatabase) = db.rateDao()
    @Provides fun provideSettingsDao(db: WalletDatabase) = db.settingsDao()
    @Provides fun provideDebtDao(db: WalletDatabase) = db.debtDao()
    @Provides fun provideDeductionDao(db: WalletDatabase) = db.deductionDao()
    @Provides fun provideCategoryRoleDao(db: WalletDatabase) = db.categoryRoleDao()
    @Provides fun provideLegacyRowDao(db: WalletDatabase) = db.legacyRowDao()
}
