package org.aristonis.mywallet.ui

import android.app.UiModeManager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.ThemePreference
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun delayedSettings_remainsLoadingUntilSavedSettingsEmitThenShowsHome() = runTest {
        val settings = DelayedSettingsRepository()
        val vm = AppViewModel(settings)
        backgroundScope.launch { vm.startDestination.collect {} }
        runCurrent()

        assertEquals(StartDestination.LOADING, vm.startDestination.value)

        settings.emit(Settings(baseCurrencyCode = "USD"))
        runCurrent()

        assertEquals(StartDestination.HOME, vm.startDestination.value)
    }

    @Test
    fun delayedSettings_remainsLoadingUntilAbsentSettingsEmitThenShowsOnboarding() = runTest {
        val settings = DelayedSettingsRepository()
        val vm = AppViewModel(settings)
        backgroundScope.launch { vm.startDestination.collect {} }
        runCurrent()

        assertEquals(StartDestination.LOADING, vm.startDestination.value)

        settings.emit(null)
        runCurrent()

        assertEquals(StartDestination.ONBOARDING, vm.startDestination.value)
    }

    @Test
    fun loadedTheme_waitsForSettingsThenEmitsSavedAndRestoredChoices() = runTest {
        val settings = DelayedSettingsRepository()
        val vm = AppViewModel(settings)
        val themes = mutableListOf<ThemePreference>()
        backgroundScope.launch { vm.loadedTheme.collect { themes.add(it) } }
        runCurrent()
        assertTrue(themes.isEmpty())

        settings.emit(Settings(baseCurrencyCode = "USD", theme = ThemePreference.DARK))
        runCurrent()
        assertEquals(listOf(ThemePreference.DARK), themes)

        settings.emit(Settings(baseCurrencyCode = "EUR", theme = ThemePreference.LIGHT))
        runCurrent()
        assertEquals(listOf(ThemePreference.DARK, ThemePreference.LIGHT), themes)
    }

    @Test
    fun loadedTheme_usesSystemForFreshInstallAfterSettingsEmit() = runTest {
        val settings = DelayedSettingsRepository()
        val vm = AppViewModel(settings)
        val themes = mutableListOf<ThemePreference>()
        backgroundScope.launch { vm.loadedTheme.collect { themes.add(it) } }
        runCurrent()

        settings.emit(null)
        runCurrent()

        assertEquals(listOf(ThemePreference.SYSTEM), themes)
    }

    @Test
    fun loadedTheme_syncsSavedAndRestoredModesWithoutInitialOrDuplicateCalls() = runTest {
        val settings = DelayedSettingsRepository()
        val vm = AppViewModel(settings)
        val calls = mutableListOf<Int>()
        backgroundScope.launch {
            vm.loadedTheme.collect { theme ->
                vm.splashNightMode.apply(theme, sdkInt = 31, setMode = calls::add)
            }
        }
        runCurrent()
        assertTrue(calls.isEmpty())

        settings.emit(Settings(baseCurrencyCode = "USD", theme = ThemePreference.DARK))
        runCurrent()
        assertEquals(listOf(UiModeManager.MODE_NIGHT_YES), calls)

        settings.save(Settings(baseCurrencyCode = "EUR", theme = ThemePreference.LIGHT))
        runCurrent()
        settings.save(Settings(baseCurrencyCode = "EUR", theme = ThemePreference.LIGHT))
        runCurrent()

        assertEquals(listOf(UiModeManager.MODE_NIGHT_YES, UiModeManager.MODE_NIGHT_NO), calls)
    }

    @Test
    fun noSettings_routesToOnboarding() = runTest {
        val vm = AppViewModel(FakeSettingsRepository(null))
        // startDestination uses WhileSubscribed, so activate it with a collector.
        backgroundScope.launch { vm.startDestination.collect {} }
        advanceUntilIdle()

        assertEquals(StartDestination.ONBOARDING, vm.startDestination.value)
    }

    @Test
    fun settingsExist_routesToHome() = runTest {
        val vm = AppViewModel(FakeSettingsRepository(Settings(baseCurrencyCode = "USD")))
        backgroundScope.launch { vm.startDestination.collect {} }
        advanceUntilIdle()

        assertEquals(StartDestination.HOME, vm.startDestination.value)
    }

    @Test
    fun completingOnboarding_flipsOnboardingToHome() = runTest {
        val settings = FakeSettingsRepository(null)
        val vm = AppViewModel(settings)
        backgroundScope.launch { vm.startDestination.collect {} }
        advanceUntilIdle()
        assertEquals(StartDestination.ONBOARDING, vm.startDestination.value)

        settings.save(Settings(baseCurrencyCode = "USD")) // onboarding writes settings
        advanceUntilIdle()

        assertEquals(StartDestination.HOME, vm.startDestination.value)
    }

    @Test
    fun theme_defaultsToSystemBeforeSettingsLoad() = runTest {
        val vm = AppViewModel(FakeSettingsRepository(null))
        // The initial value before the settings flow emits — a fresh install follows the OS.
        assertEquals(ThemePreference.SYSTEM, vm.theme.value)
    }

    @Test
    fun theme_reflectsTheSavedChoiceOnceLoaded() = runTest {
        val vm = AppViewModel(FakeSettingsRepository(Settings(baseCurrencyCode = "USD", theme = ThemePreference.DARK)))
        backgroundScope.launch { vm.theme.collect {} }
        advanceUntilIdle()

        assertEquals(ThemePreference.DARK, vm.theme.value)
    }
}

private class FakeSettingsRepository(initial: Settings?) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override fun observe(): Flow<Settings> = state.filterNotNull()
    override fun observeOrNull(): Flow<Settings?> = state
    override suspend fun get(): Settings = state.value ?: error("settings not initialized")
    override suspend fun save(settings: Settings) { state.value = settings }
}

private class DelayedSettingsRepository : SettingsRepository {
    private val emissions = MutableSharedFlow<Settings?>()
    override fun observe(): Flow<Settings> = emissions.filterNotNull()
    override fun observeOrNull(): Flow<Settings?> = emissions
    override suspend fun get(): Settings = error("settings have not loaded")
    override suspend fun save(settings: Settings) { emissions.emit(settings) }
    suspend fun emit(settings: Settings?) { emissions.emit(settings) }
}
