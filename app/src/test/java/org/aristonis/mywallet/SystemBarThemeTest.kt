package org.aristonis.mywallet

import org.aristonis.mywallet.domain.model.ThemePreference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemBarThemeTest {
    @Test
    fun savedDarkThemeUsesLightStatusContentWhenAndroidIsLight() {
        assertFalse(shouldUseDarkStatusBarIcons(ThemePreference.DARK, systemDark = false))
    }

    @Test
    fun savedLightThemeUsesDarkStatusContentWhenAndroidIsDark() {
        assertTrue(shouldUseDarkStatusBarIcons(ThemePreference.LIGHT, systemDark = true))
    }

    @Test
    fun systemThemeFollowsAndroidForStatusContent() {
        assertTrue(shouldUseDarkStatusBarIcons(ThemePreference.SYSTEM, systemDark = false))
        assertFalse(shouldUseDarkStatusBarIcons(ThemePreference.SYSTEM, systemDark = true))
    }
}
