package org.aristonis.mywallet.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.port.TransactionRepository
import org.aristonis.mywallet.domain.usecase.ComputeCategoryBreakdown
import org.aristonis.mywallet.domain.usecase.ComputeNetWorth
import org.aristonis.mywallet.domain.usecase.ComputePeriodSummary
import org.aristonis.mywallet.domain.usecase.CreateAccount
import org.aristonis.mywallet.domain.usecase.DeleteAccount
import org.aristonis.mywallet.domain.usecase.DeleteTransaction
import org.aristonis.mywallet.domain.usecase.GetAccountBalances
import org.aristonis.mywallet.domain.usecase.GetAccountBalancesInBase
import org.aristonis.mywallet.domain.usecase.RecordExpense
import org.aristonis.mywallet.domain.usecase.RecordIncome
import org.aristonis.mywallet.domain.usecase.RecordTransfer
import org.aristonis.mywallet.domain.usecase.SetAccountArchived
import org.aristonis.mywallet.domain.usecase.SetBaseCurrency
import org.aristonis.mywallet.domain.usecase.SetExchangeRate
import org.aristonis.mywallet.domain.usecase.UpdateAccount
import org.aristonis.mywallet.domain.usecase.UpdateTransaction
import javax.inject.Singleton

/**
 * Provides domain use-cases to the app. Use-cases are plain `:domain` classes (no Hilt annotations —
 * the domain never depends on Android/Hilt), so the app assembles them here from the ports that
 * [RepositoryModule] already binds. Grown as ViewModels need more use-cases (YAGNI).
 */
@Module
@InstallIn(SingletonComponent::class)
object UseCaseModule {

    @Provides @Singleton
    fun provideSetBaseCurrency(
        currencies: CurrencyRepository,
        settings: SettingsRepository,
    ): SetBaseCurrency = SetBaseCurrency(currencies, settings)

    @Provides @Singleton
    fun provideCreateAccount(
        currencies: CurrencyRepository,
        accounts: AccountRepository,
    ): CreateAccount = CreateAccount(currencies, accounts)

    @Provides @Singleton
    fun provideSetAccountArchived(accounts: AccountRepository): SetAccountArchived = SetAccountArchived(accounts)

    @Provides @Singleton
    fun provideDeleteAccount(
        accounts: AccountRepository,
        transactions: TransactionRepository,
    ): DeleteAccount = DeleteAccount(accounts, transactions)

    @Provides @Singleton
    fun provideUpdateAccount(
        currencies: CurrencyRepository,
        accounts: AccountRepository,
        transactions: TransactionRepository,
    ): UpdateAccount = UpdateAccount(currencies, accounts, transactions)

    @Provides @Singleton
    fun provideSetExchangeRate(
        currencies: CurrencyRepository,
        rates: RateRepository,
    ): SetExchangeRate = SetExchangeRate(currencies, rates)

    @Provides @Singleton
    fun provideGetAccountBalances(
        accounts: AccountRepository,
        transactions: TransactionRepository,
    ): GetAccountBalances = GetAccountBalances(accounts, transactions)

    @Provides @Singleton
    fun provideComputeNetWorth(
        getAccountBalances: GetAccountBalances,
        currencies: CurrencyRepository,
        rates: RateRepository,
        settings: SettingsRepository,
    ): ComputeNetWorth = ComputeNetWorth(getAccountBalances, currencies, rates, settings)

    @Provides @Singleton
    fun provideGetAccountBalancesInBase(
        getAccountBalances: GetAccountBalances,
        currencies: CurrencyRepository,
        rates: RateRepository,
        settings: SettingsRepository,
    ): GetAccountBalancesInBase = GetAccountBalancesInBase(getAccountBalances, currencies, rates, settings)

    @Provides @Singleton
    fun provideComputePeriodSummary(
        transactions: TransactionRepository,
        currencies: CurrencyRepository,
        rates: RateRepository,
        settings: SettingsRepository,
    ): ComputePeriodSummary = ComputePeriodSummary(transactions, currencies, rates, settings)

    @Provides @Singleton
    fun provideComputeCategoryBreakdown(
        transactions: TransactionRepository,
        currencies: CurrencyRepository,
        rates: RateRepository,
        settings: SettingsRepository,
    ): ComputeCategoryBreakdown = ComputeCategoryBreakdown(transactions, currencies, rates, settings)

    @Provides @Singleton
    fun provideRecordIncome(
        accounts: AccountRepository,
        categories: CategoryRepository,
        transactions: TransactionRepository,
    ): RecordIncome = RecordIncome(accounts, categories, transactions)

    @Provides @Singleton
    fun provideRecordExpense(
        accounts: AccountRepository,
        categories: CategoryRepository,
        transactions: TransactionRepository,
    ): RecordExpense = RecordExpense(accounts, categories, transactions)

    @Provides @Singleton
    fun provideRecordTransfer(
        accounts: AccountRepository,
        currencies: CurrencyRepository,
        rates: RateRepository,
        settings: SettingsRepository,
        transactions: TransactionRepository,
    ): RecordTransfer = RecordTransfer(accounts, currencies, rates, settings, transactions)

    @Provides @Singleton
    fun provideUpdateTransaction(
        accounts: AccountRepository,
        categories: CategoryRepository,
        currencies: CurrencyRepository,
        transactions: TransactionRepository,
    ): UpdateTransaction = UpdateTransaction(accounts, categories, currencies, transactions)

    @Provides @Singleton
    fun provideDeleteTransaction(
        transactions: TransactionRepository,
    ): DeleteTransaction = DeleteTransaction(transactions)
}
