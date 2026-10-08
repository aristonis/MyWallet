package org.aristonis.mywallet.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.aristonis.mywallet.data.db.AccountDao
import org.aristonis.mywallet.data.db.CategoryDao
import org.aristonis.mywallet.data.db.CurrencyDao
import org.aristonis.mywallet.data.db.RateDao
import org.aristonis.mywallet.data.db.SettingsDao
import org.aristonis.mywallet.data.db.TransactionDao
import org.aristonis.mywallet.data.db.WalletDatabase
import org.aristonis.mywallet.data.backup.RoomBackupRepository
import org.aristonis.mywallet.data.repo.RoomAccountRepository
import org.aristonis.mywallet.data.repo.RoomCategoryRepository
import org.aristonis.mywallet.data.repo.RoomCurrencyRepository
import org.aristonis.mywallet.data.repo.RoomRateRepository
import org.aristonis.mywallet.data.repo.RoomSettingsRepository
import org.aristonis.mywallet.data.repo.RoomTransactionRepository
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.BackupRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.port.TransactionRepository
import javax.inject.Singleton
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.data.repo.RoomFxRepository
import org.aristonis.mywallet.domain.port.BaseCurrencyRepository
import org.aristonis.mywallet.data.repo.RoomBaseCurrencyRepository
import org.aristonis.mywallet.data.repo.RoomTransactionRunner
import org.aristonis.mywallet.domain.port.TransactionRunner

/** Binds each domain port to its Room implementation. This is the seam where the app chooses Room. */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides @Singleton
    fun provideAccountRepository(dao: AccountDao): AccountRepository = RoomAccountRepository(dao)

    @Provides @Singleton
    fun provideTransactionRepository(dao: TransactionDao): TransactionRepository = RoomTransactionRepository(dao)

    @Provides @Singleton
    fun provideCategoryRepository(dao: CategoryDao): CategoryRepository = RoomCategoryRepository(dao)

    @Provides @Singleton
    fun provideCurrencyRepository(dao: CurrencyDao): CurrencyRepository = RoomCurrencyRepository(dao)

    @Provides @Singleton
    fun provideRateRepository(dao: RateDao): RateRepository = RoomRateRepository(dao)

    @Provides @Singleton
    fun provideSettingsRepository(dao: SettingsDao): SettingsRepository = RoomSettingsRepository(dao)

    @Provides @Singleton
    fun provideBaseCurrencyRepository(db: WalletDatabase): BaseCurrencyRepository =
        RoomBaseCurrencyRepository(db)

    @Provides @Singleton
    fun provideFxRepository(db: WalletDatabase): FxRepository = RoomFxRepository(db)

    @Provides @Singleton
    fun provideTransactionRunner(db: WalletDatabase): TransactionRunner = RoomTransactionRunner(db)

    @Provides @Singleton
    fun provideBackupRepository(
        database: WalletDatabase,
        accountDao: AccountDao,
        transactionDao: TransactionDao,
        categoryDao: CategoryDao,
        currencyDao: CurrencyDao,
        rateDao: RateDao,
        settingsDao: SettingsDao,
    ): BackupRepository = RoomBackupRepository(
        database, accountDao, transactionDao, categoryDao, currencyDao, rateDao, settingsDao,
    )
}
