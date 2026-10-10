package org.aristonis.mywallet.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.aristonis.mywallet.domain.port.DebtRepository
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.TransactionRunner
import org.aristonis.mywallet.domain.usecase.ObserveDebt
import org.aristonis.mywallet.domain.usecase.ObserveDebts
import org.aristonis.mywallet.domain.usecase.RenameDebt
import org.aristonis.mywallet.domain.usecase.SuggestCounterparties
import org.aristonis.mywallet.domain.usecase.UpdateDebtDetails
import org.aristonis.mywallet.ui.debt.TodaySource
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DebtUseCaseModule {
    @Provides @Singleton
    fun provideObserveDebts(
        debts: DebtRepository,
        ledger: LedgerQueries,
        fx: FxRepository,
        today: TodaySource,
    ): ObserveDebts = ObserveDebts(debts, ledger, fx, today.dates)

    @Provides @Singleton
    fun provideObserveDebt(
        debts: DebtRepository,
        ledger: LedgerQueries,
        entries: EntryRepository,
        today: TodaySource,
    ): ObserveDebt = ObserveDebt(debts, ledger, entries, today.dates)

    @Provides @Singleton
    fun provideRenameDebt(runner: TransactionRunner, debts: DebtRepository): RenameDebt =
        RenameDebt(runner, debts)

    @Provides @Singleton
    fun provideUpdateDebtDetails(runner: TransactionRunner, debts: DebtRepository): UpdateDebtDetails =
        UpdateDebtDetails(runner, debts)

    @Provides @Singleton
    fun provideSuggestCounterparties(debts: DebtRepository): SuggestCounterparties =
        SuggestCounterparties(debts)
}
