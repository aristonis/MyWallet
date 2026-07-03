package org.aristonis.mywallet.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.port.TransactionRepository
import org.aristonis.mywallet.domain.usecase.ComputeNetWorth
import org.aristonis.mywallet.domain.usecase.CreateAccount
import org.aristonis.mywallet.domain.usecase.GetAccountBalances
import org.aristonis.mywallet.domain.usecase.GetAccountBalancesInBase
import org.aristonis.mywallet.domain.usecase.SetBaseCurrency
import org.aristonis.mywallet.domain.usecase.SetExchangeRate
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
}
