package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.port.SettingsRepository

/**
 * Turns on or off keeping rates typed on transaction forms as the saved rates. Like the theme, the
 * switch is only reachable after onboarding, so missing settings fail loud instead of being invented.
 */
class SetSaveTransactionRates(
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(enabled: Boolean) {
        val current = settings.get()
        settings.save(current.copy(saveTransactionRates = enabled))
    }
}
