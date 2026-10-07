package org.aristonis.mywallet.ui.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.usecase.ExportBackup
import org.aristonis.mywallet.domain.usecase.RestoreBackup
import org.aristonis.mywallet.domain.usecase.SetTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The Settings list shows the base currency as the row's current value, so the screen has to be able
 * to read it. It tracks the live settings rather than a value captured once, or changing the base
 * currency would leave the row stating the old one until the app restarted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsBaseCurrencyTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(settings: FakeSettingsRepository) = SettingsViewModel(
        exportBackup = ExportBackup(FakeBackupRepository()),
        restoreBackup = RestoreBackup(FakeBackupRepository()),
        documentIo = FakeDocumentIo(),
        setTheme = SetTheme(settings),
        settings = settings,
    )

    @Test
    fun `the saved base currency is what the row reports`() = runTest {
        val vm = viewModel(FakeSettingsRepository(Settings(baseCurrencyCode = "EUR")))
        backgroundScope.launch { vm.baseCurrencyCode.collect {} }
        advanceUntilIdle()

        assertEquals("EUR", vm.baseCurrencyCode.value)
    }

    @Test
    fun `changing the base currency moves the row with it`() = runTest {
        val settings = FakeSettingsRepository(Settings(baseCurrencyCode = "USD"))
        val vm = viewModel(settings)
        backgroundScope.launch { vm.baseCurrencyCode.collect {} }
        advanceUntilIdle()

        settings.save(Settings(baseCurrencyCode = "JPY"))
        advanceUntilIdle()

        assertEquals("JPY", vm.baseCurrencyCode.value)
    }

    /** Before onboarding writes settings there is no base currency, and inventing one would lie. */
    @Test
    fun `no settings yet reports nothing rather than a guess`() = runTest {
        val vm = viewModel(FakeSettingsRepository(null))
        backgroundScope.launch { vm.baseCurrencyCode.collect {} }
        advanceUntilIdle()

        assertNull(vm.baseCurrencyCode.value)
    }
}
