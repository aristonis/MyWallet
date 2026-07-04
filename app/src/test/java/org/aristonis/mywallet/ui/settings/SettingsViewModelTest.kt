package org.aristonis.mywallet.ui.settings

import android.net.Uri
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
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.ThemePreference
import org.aristonis.mywallet.domain.port.BackupRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.usecase.ExportBackup
import org.aristonis.mywallet.domain.usecase.RestoreBackup
import org.aristonis.mywallet.domain.usecase.SetTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Backup half: exercises the Working -> Success/Error state machine + error mapping over the
 * [Uri]-free cores (`runExport`/`runImport`/`applyRestore`), which take the IO step as a lambda.
 * Theme half: selecting a theme runs [SetTheme] and the observed current theme reflects the saved
 * value. The thin `Uri` -> DocumentIo glue is left for device tests, so these stay pure-JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        repo: FakeBackupRepository,
        settings: FakeSettingsRepository = FakeSettingsRepository(Settings(baseCurrencyCode = "USD")),
    ) = SettingsViewModel(
        exportBackup = ExportBackup(repo),
        restoreBackup = RestoreBackup(repo),
        documentIo = FakeDocumentIo(),
        setTheme = SetTheme(settings),
        settings = settings,
    )

    // --- backup section (ported) ---

    @Test
    fun export_success_writesTheBuiltTextAndReportsSuccess() = runTest {
        val vm = viewModel(FakeBackupRepository(exported = "the-backup-json"))
        var written: String? = null

        vm.runExport { written = it }

        assertEquals("the-backup-json", written)
        assertEquals(BackupStatus.Success("Backup saved"), vm.uiState.value.status)
    }

    @Test
    fun export_writeFailure_reportsSaveError() = runTest {
        val vm = viewModel(FakeBackupRepository())

        vm.runExport { throw IOException("no space") }

        assertEquals(BackupStatus.Error("Couldn't save the backup"), vm.uiState.value.status)
    }

    @Test
    fun import_readFailure_reportsReadErrorAndSkipsRestore() = runTest {
        val repo = FakeBackupRepository()
        val vm = viewModel(repo)

        vm.runImport { throw IOException("can't open") }

        assertEquals(BackupStatus.Error("Couldn't read that file"), vm.uiState.value.status)
        assertNull("a read failure must not reach the restore", repo.restoredWith)
    }

    @Test
    fun import_readSuccess_appliesTheRestore() = runTest {
        val repo = FakeBackupRepository()
        val vm = viewModel(repo)

        vm.runImport { "payload" }

        assertEquals("payload", repo.restoredWith)
        assertEquals(BackupStatus.Success("Backup restored"), vm.uiState.value.status)
    }

    @Test
    fun restore_corruptBackup_reportsInvalidMessage() = runTest {
        val vm = viewModel(FakeBackupRepository(restoreFailure = WalletException.BackupInvalid()))

        vm.applyRestore("payload")

        assertEquals(
            BackupStatus.Error("This file isn't a valid MyWallet backup"),
            vm.uiState.value.status,
        )
    }

    @Test
    fun restore_newerVersionBackup_reportsVersionMessage() = runTest {
        val vm = viewModel(FakeBackupRepository(restoreFailure = WalletException.BackupVersionUnsupported(2)))

        vm.applyRestore("payload")

        assertEquals(
            BackupStatus.Error("This backup is from a newer version of the app"),
            vm.uiState.value.status,
        )
    }

    @Test
    fun acknowledge_afterTerminalStatus_returnsToIdle() = runTest {
        val vm = viewModel(FakeBackupRepository())

        vm.applyRestore("payload")
        vm.acknowledge()

        assertEquals(BackupStatus.Idle, vm.uiState.value.status)
    }

    // --- theme section (new) ---

    @Test
    fun theme_reflectsSavedSetting() = runTest {
        val settings = FakeSettingsRepository(Settings(baseCurrencyCode = "USD", theme = ThemePreference.DARK))
        val vm = viewModel(FakeBackupRepository(), settings)
        backgroundScope.launch { vm.theme.collect {} } // WhileSubscribed needs a collector
        advanceUntilIdle()

        assertEquals(ThemePreference.DARK, vm.theme.value)
    }

    @Test
    fun selectTheme_savesChoice_andCurrentThemeReflectsIt() = runTest {
        val settings = FakeSettingsRepository(Settings(baseCurrencyCode = "USD", theme = ThemePreference.SYSTEM))
        val vm = viewModel(FakeBackupRepository(), settings)
        backgroundScope.launch { vm.theme.collect {} }
        advanceUntilIdle()

        vm.selectTheme(ThemePreference.DARK)
        advanceUntilIdle()

        assertEquals(ThemePreference.DARK, settings.get().theme)
        assertEquals("USD", settings.get().baseCurrencyCode) // theme change leaves base currency alone
        assertEquals(ThemePreference.DARK, vm.theme.value)
    }
}

// App-side fakes: the domain module's own fakes aren't on the :app test classpath, so the ports are
// re-faked here. `restoreBackup` is configurable to succeed or throw a typed rejection.
private class FakeBackupRepository(
    private val exported: String = "{}",
    private val restoreFailure: WalletException? = null,
) : BackupRepository {
    var restoredWith: String? = null
        private set

    override suspend fun exportBackup(): String = exported

    override suspend fun restoreBackup(serialized: String) {
        restoreFailure?.let { throw it }
        restoredWith = serialized
    }
}

private class FakeSettingsRepository(initial: Settings?) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override fun observe(): Flow<Settings> = state.filterNotNull()
    override fun observeOrNull(): Flow<Settings?> = state
    override suspend fun get(): Settings = state.value ?: error("settings not initialized")
    override suspend fun save(settings: Settings) { state.value = settings }
}

// The tested cores take the IO step as a lambda, so this is only here to construct the view-model —
// its Uri-typed methods are never invoked in these tests, so no real android.net.Uri is built.
private class FakeDocumentIo : DocumentIo {
    override suspend fun writeText(uri: Uri, text: String) = Unit
    override suspend fun readText(uri: Uri): String = "{}"
}
