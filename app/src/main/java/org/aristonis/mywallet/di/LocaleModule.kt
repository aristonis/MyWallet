package org.aristonis.mywallet.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.format.MoneyParser
import java.util.Currency
import java.util.Locale
import javax.inject.Singleton

/**
 * Environment defaults derived from the device locale. Wrapped in a type (not a bare String) so it
 * injects unambiguously and so ViewModels can be unit-tested with a fixed value instead of the host
 * machine's locale. [currencyCode] is null when the default locale has no associated currency.
 */
data class LocaleDefaults(val currencyCode: String?)

@Module
@InstallIn(SingletonComponent::class)
object LocaleModule {

    @Provides
    fun provideLocaleDefaults(): LocaleDefaults =
        LocaleDefaults(
            runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrNull(),
        )

    @Provides
    @Singleton
    fun provideMoneyFormatter(): MoneyFormatter = MoneyFormatter(Locale.getDefault())

    @Provides
    @Singleton
    fun provideMoneyParser(): MoneyParser = MoneyParser(Locale.getDefault())
}
