package org.aristonis.mywallet.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.port.TransactionRunner
import org.aristonis.mywallet.domain.usecase.DeleteEntry
import org.aristonis.mywallet.domain.usecase.RestoreEntries
import org.aristonis.mywallet.domain.usecase.SaveEntry
import org.aristonis.mywallet.domain.usecase.SetExchangeRate
import javax.inject.Singleton

/**
 * The use cases that write entries: save (new or edited, any kind), delete, and undo a delete. Each
 * runs inside one database transaction through [TransactionRunner], so a refused write leaves
 * nothing behind.
 */
@Module
@InstallIn(SingletonComponent::class)
object LedgerUseCaseModule {

    @Provides @Singleton
    fun provideSaveEntry(
        runner: TransactionRunner,
        accounts: AccountRepository,
        categories: CategoryRepository,
        entries: EntryRepository,
        ledger: LedgerQueries,
        fx: FxRepository,
        settings: SettingsRepository,
        setExchangeRate: SetExchangeRate,
    ): SaveEntry = SaveEntry(runner, accounts, categories, entries, ledger, fx, settings, setExchangeRate)

    @Provides @Singleton
    fun provideDeleteEntry(
        runner: TransactionRunner,
        entries: EntryRepository,
    ): DeleteEntry = DeleteEntry(runner, entries)

    @Provides @Singleton
    fun provideRestoreEntries(
        runner: TransactionRunner,
        entries: EntryRepository,
        categories: CategoryRepository,
        accounts: AccountRepository,
    ): RestoreEntries = RestoreEntries(runner, entries, categories, accounts)
}
