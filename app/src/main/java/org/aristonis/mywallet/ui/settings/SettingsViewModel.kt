package org.aristonis.mywallet.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.domain.model.ThemePreference
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.usecase.ExportBackup
import org.aristonis.mywallet.domain.usecase.RestoreBackup
import org.aristonis.mywallet.domain.usecase.SetTheme
import javax.inject.Inject

/**
 * What the backup section is currently doing. A terminal Success/Error names the outcome rather than
 * wording it, so this view model needs no `Context` and the copy stays in one resource file.
 */
sealed interface BackupStatus {
    data object Idle : BackupStatus
    data object Working : BackupStatus
    data class Success(val message: UiMessage) : BackupStatus
    data class Error(val message: UiMessage) : BackupStatus
}

/** Immutable snapshot the settings screen renders the backup section from. */
data class BackupUiState(val status: BackupStatus = BackupStatus.Idle)

/**
 * Drives the settings screen: the backup section (export/restore) and the appearance section (theme).
 *
 * Backup: the screen owns the Storage Access Framework pickers and hands this a [Uri]; here the [Uri]
 * is only passed through to [DocumentIo]. The actual work is done by [Uri]-free `internal` functions
 * that take the IO step as a lambda, so the whole Working -> Success/Error state machine (and the
 * error mapping) is unit-testable without a real `android.net.Uri`.
 *
 * Theme: [theme] mirrors the saved choice off the live settings flow so the selector shows what's in
 * effect; [selectTheme] persists a new choice via [SetTheme]. Saving re-emits on the flow, so both
 * the selector highlight and the app-wide colours (driven from `MainActivity`) update with no restart.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val exportBackup: ExportBackup,
    private val restoreBackup: RestoreBackup,
    private val documentIo: DocumentIo,
    private val setTheme: SetTheme,
    settings: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    val theme: StateFlow<ThemePreference> =
        settings.observeOrNull()
            .map { it?.theme ?: ThemePreference.SYSTEM }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemePreference.SYSTEM)

    /**
     * What the Base currency row states as its current value. Read off the live settings so changing
     * the base currency moves the row with it, rather than leaving the old code there until restart.
     * Null until onboarding has written settings — there is nothing to state yet, and a guess would
     * be wrong.
     */
    val baseCurrencyCode: StateFlow<String?> =
        settings.observeOrNull()
            .map { it?.baseCurrencyCode }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Persist the chosen theme; the settings flow then reskins the app and moves the selection. */
    fun selectTheme(choice: ThemePreference) {
        viewModelScope.launch { setTheme(choice) }
    }

    /** Serialize the wallet and write it to the chosen document. */
    fun exportTo(uri: Uri) {
        viewModelScope.launch { runExport { text -> documentIo.writeText(uri, text) } }
    }

    /** Read the chosen document and replace the wallet with it. */
    fun importFrom(uri: Uri) {
        viewModelScope.launch { runImport { documentIo.readText(uri) } }
    }

    /** Reset to Idle once the UI has shown the terminal message. */
    fun acknowledge() {
        _uiState.value = BackupUiState(BackupStatus.Idle)
    }

    /**
     * Export core: produce the snapshot and hand it to [write]. `internal` and [Uri]-free so a test
     * can assert the Working/Success/Error transitions and that the built text is what gets written.
     */
    internal suspend fun runExport(write: suspend (String) -> Unit) {
        if (_uiState.value.status is BackupStatus.Working) return
        _uiState.value = BackupUiState(BackupStatus.Working)
        try {
            write(exportBackup())
            _uiState.value = BackupUiState(BackupStatus.Success(UiMessage.BackupSaved))
        } catch (e: CancellationException) {
            throw e // honour structured concurrency — never swallow cancellation
        } catch (e: Exception) {
            _uiState.value = BackupUiState(BackupStatus.Error(UiMessage.BackupSaveFailed))
        }
    }

    /**
     * Import core: [read] the chosen document, then apply it. A read failure is distinct from a file
     * that reads but can't be applied, so it stops here with its own message.
     */
    internal suspend fun runImport(read: suspend () -> String) {
        if (_uiState.value.status is BackupStatus.Working) return
        _uiState.value = BackupUiState(BackupStatus.Working)
        val text = try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.value = BackupUiState(BackupStatus.Error(UiMessage.BackupFileUnreadable))
            return
        }
        applyRestore(text)
    }

    /** Restore core: apply the decoded text and name each typed rejection. */
    internal suspend fun applyRestore(text: String) {
        try {
            restoreBackup(text)
            _uiState.value = BackupUiState(BackupStatus.Success(UiMessage.BackupRestored))
        } catch (e: WalletException.BackupInvalid) {
            _uiState.value = BackupUiState(BackupStatus.Error(UiMessage.BackupNotRecognized))
        } catch (e: WalletException.BackupVersionUnsupported) {
            _uiState.value = BackupUiState(BackupStatus.Error(UiMessage.BackupFromNewerVersion))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The file read fine but applying it failed (e.g. a database write error) — surface a
            // restore failure, not the read-failure copy, and never leave it silently swallowed.
            _uiState.value = BackupUiState(BackupStatus.Error(UiMessage.BackupRestoreFailed))
        }
    }
}
