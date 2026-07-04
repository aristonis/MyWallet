package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.model.ThemePreference
import org.aristonis.mywallet.domain.port.SettingsRepository

/**
 * Changes the app theme (used by the settings screen). The selector is only reachable after
 * onboarding, so settings must already exist — [SettingsRepository.get] fails loud otherwise rather
 * than inventing a Settings to write. Only the theme changes; the base currency is left untouched.
 */
class SetTheme(
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(theme: ThemePreference) {
        val current = settings.get()
        settings.save(current.copy(theme = theme))
    }
}
