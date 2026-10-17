package org.aristonis.mywallet.ui.theme

import android.app.UiModeManager
import org.aristonis.mywallet.domain.model.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashNightModeTest {
    @Test
    fun applicationModes_matchSavedThemeChoices() {
        val cases = listOf(
            ThemePreference.SYSTEM to UiModeManager.MODE_NIGHT_AUTO,
            ThemePreference.LIGHT to UiModeManager.MODE_NIGHT_NO,
            ThemePreference.DARK to UiModeManager.MODE_NIGHT_YES,
        )
        cases.forEach { (theme, expected) ->
            val calls = mutableListOf<Int>()
            val synchronizer = SplashNightMode()

            synchronizer.apply(theme, sdkInt = 31, setMode = calls::add)

            assertEquals(listOf(expected), calls)
        }
    }

    @Test
    fun olderAndroidVersions_leaveApplicationModeUntouched() {
        val calls = mutableListOf<Int>()
        val synchronizer = SplashNightMode()

        listOf(28, 30).forEach { sdk ->
            ThemePreference.entries.forEach { theme ->
                synchronizer.apply(theme, sdkInt = sdk, setMode = calls::add)
            }
        }

        assertTrue(calls.isEmpty())
    }

    @Test
    fun repeatedMode_doesNotCallSetterAgain() {
        val calls = mutableListOf<Int>()
        val synchronizer = SplashNightMode()
        synchronizer.apply(ThemePreference.DARK, sdkInt = 36, setMode = calls::add)

        synchronizer.apply(ThemePreference.DARK, sdkInt = 36, setMode = calls::add)

        assertEquals(listOf(UiModeManager.MODE_NIGHT_YES), calls)
    }

    @Test
    fun restoredThemeChange_appliesNewModeThenSkipsDuplicates() {
        val calls = mutableListOf<Int>()
        val synchronizer = SplashNightMode()
        synchronizer.apply(ThemePreference.DARK, sdkInt = 36, setMode = calls::add)

        synchronizer.apply(ThemePreference.LIGHT, sdkInt = 36, setMode = calls::add)
        synchronizer.apply(ThemePreference.LIGHT, sdkInt = 36, setMode = calls::add)
        synchronizer.apply(ThemePreference.SYSTEM, sdkInt = 36, setMode = calls::add)

        assertEquals(listOf(UiModeManager.MODE_NIGHT_YES, UiModeManager.MODE_NIGHT_NO,
            UiModeManager.MODE_NIGHT_AUTO), calls)
    }

    @Test
    fun failedSetter_isPropagatedAndSameThemeCanBeRetried() {
        val synchronizer = SplashNightMode()
        assertThrows(IllegalStateException::class.java) {
            synchronizer.apply(ThemePreference.DARK, sdkInt = 31) {
                throw IllegalStateException("platform unavailable")
            }
        }
        val calls = mutableListOf<Int>()

        synchronizer.apply(ThemePreference.DARK, sdkInt = 31, setMode = calls::add)

        assertEquals(listOf(UiModeManager.MODE_NIGHT_YES), calls)
    }

    @Test
    fun retainedSynchronizer_appliesChangedThemeToNewActivitySetterOnly() {
        val oldActivityCalls = mutableListOf<Int>()
        val newActivityCalls = mutableListOf<Int>()
        val synchronizer = SplashNightMode()
        synchronizer.apply(ThemePreference.DARK, sdkInt = 31, setMode = oldActivityCalls::add)

        synchronizer.apply(ThemePreference.DARK, sdkInt = 31, setMode = newActivityCalls::add)
        synchronizer.apply(ThemePreference.LIGHT, sdkInt = 31, setMode = newActivityCalls::add)

        assertEquals(listOf(UiModeManager.MODE_NIGHT_YES), oldActivityCalls)
        assertEquals(listOf(UiModeManager.MODE_NIGHT_NO), newActivityCalls)
    }
}
