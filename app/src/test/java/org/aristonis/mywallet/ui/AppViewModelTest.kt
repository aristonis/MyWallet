package org.aristonis.mywallet.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

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
}

private class FakeSettingsRepository(initial: Settings?) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override fun observe(): Flow<Settings> = state.filterNotNull()
    override fun observeOrNull(): Flow<Settings?> = state
    override suspend fun get(): Settings = state.value ?: error("settings not initialized")
    override suspend fun save(settings: Settings) { state.value = settings }
}
