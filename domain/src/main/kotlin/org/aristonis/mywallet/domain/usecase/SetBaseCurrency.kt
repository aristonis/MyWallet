package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.ThemePreference
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.SettingsRepository

/** Sets the app base currency (used by onboarding and the settings screen). */
class SetBaseCurrency(
    private val currencies: CurrencyRepository,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(
        currencyCode: String,
        theme: ThemePreference = ThemePreference.SYSTEM,
    ) {
        currencies.findByCode(currencyCode)
            ?: throw WalletException.CurrencyNotFound(currencyCode)
        settings.save(Settings(baseCurrencyCode = currencyCode, theme = theme))
    }
}
