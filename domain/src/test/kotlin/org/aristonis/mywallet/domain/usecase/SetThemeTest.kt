package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.ThemePreference
import org.aristonis.mywallet.domain.usecase.fake.FakeSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class SetThemeTest {

    @Test
    fun savesSettingsWithChosenTheme_keepingBaseCurrency() = runTest {
        val settings = FakeSettingsRepository(
            Settings(baseCurrencyCode = "USD", theme = ThemePreference.SYSTEM),
        )

        SetTheme(settings).invoke(ThemePreference.DARK)

        val saved = settings.get()
        assertEquals(ThemePreference.DARK, saved.theme)
        assertEquals("USD", saved.baseCurrencyCode)
    }

    // The theme selector is only reachable after onboarding, so no settings row is a bug, not a
    // silent default — reading it must throw rather than invent a Settings to write.
    @Test(expected = IllegalStateException::class)
    fun missingSettings_failsLoud() = runTest {
        SetTheme(FakeSettingsRepository()).invoke(ThemePreference.LIGHT)
    }
}
