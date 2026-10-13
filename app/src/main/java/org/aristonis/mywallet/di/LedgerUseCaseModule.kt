package org.aristonis.mywallet.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CategoryRoleRepository
import org.aristonis.mywallet.domain.port.CsvEncoder
import org.aristonis.mywallet.domain.port.CsvExportSource
import org.aristonis.mywallet.domain.port.DebtRepository
import org.aristonis.mywallet.domain.port.DeductionItemRepository
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.port.TransactionRunner
import org.aristonis.mywallet.domain.service.RoleCategories
import org.aristonis.mywallet.domain.usecase.DeleteEntry
import org.aristonis.mywallet.domain.usecase.ExportTrackingCsv
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
    fun provideExportTrackingCsv(source: CsvExportSource, encoder: CsvEncoder): ExportTrackingCsv =
        ExportTrackingCsv(source, encoder)

    @Provides @Singleton
    fun provideRoleCategories(roles: CategoryRoleRepository, categories: CategoryRepository): RoleCategories =
        RoleCategories(roles, categories)

    @Provides @Singleton
    fun provideSaveEntry(
        runner: TransactionRunner,
        accounts: AccountRepository,
        categories: CategoryRepository,
        entries: EntryRepository,
        fx: FxRepository,
        settings: SettingsRepository,
        setExchangeRate: SetExchangeRate,
        debts: DebtRepository,
        ledger: LedgerQueries,
        roleCategories: RoleCategories,
        deductions: DeductionItemRepository,
    ): SaveEntry = SaveEntry(
        runner, accounts, categories, entries, fx, settings, setExchangeRate, debts, ledger, roleCategories, deductions,
    )

    @Provides @Singleton
    fun provideDeleteEntry(
        runner: TransactionRunner,
        entries: EntryRepository,
        debts: DebtRepository,
        ledger: LedgerQueries,
    ): DeleteEntry = DeleteEntry(runner, entries, debts, ledger)

    @Provides @Singleton
    fun provideRestoreEntries(
        runner: TransactionRunner,
        entries: EntryRepository,
        categories: CategoryRepository,
        accounts: AccountRepository,
        debts: DebtRepository,
        ledger: LedgerQueries,
        deductions: DeductionItemRepository,
    ): RestoreEntries = RestoreEntries(runner, entries, categories, accounts, debts, ledger, deductions)
}
