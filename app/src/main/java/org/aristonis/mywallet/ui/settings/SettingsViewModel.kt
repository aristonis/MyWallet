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
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.ThemePreference
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.usecase.ExportBackup
import org.aristonis.mywallet.domain.usecase.RestoreBackup
import org.aristonis.mywallet.domain.usecase.SetSaveTransactionRates
import org.aristonis.mywallet.domain.usecase.SetTheme
import org.aristonis.mywallet.ui.message.UiMessage
import java.io.InputStream
import java.io.OutputStream
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
 * that take the stream IO step as a lambda, so the whole Working -> Success/Error state machine (and the
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
    private val setSaveTransactionRates: SetSaveTransactionRates,
    settings: SettingsRepository,
    private val errors: ErrorReporter,
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

    /**
     * Whether a rate typed on a transaction form also becomes the saved rate. Shown as on until
     * settings load, matching the stored default, so the switch does not flick off and back.
     */
    val saveTransactionRates: StateFlow<Boolean> =
        settings.observeOrNull()
            .map { it?.saveTransactionRates ?: true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setSaveTransactionRates(enabled: Boolean) {
        // `.invoke` names the use case; a bare call would resolve to this function and recurse.
        viewModelScope.launch { setSaveTransactionRates.invoke(enabled) }
    }

    /** Persist the chosen theme; the settings flow then reskins the app and moves the selection. */
    fun selectTheme(choice: ThemePreference) {
        viewModelScope.launch { setTheme(choice) }
    }

    /** Serialize the wallet and write it to the chosen document. */
    fun exportTo(uri: Uri) {
        viewModelScope.launch { runExport { write -> documentIo.write(uri, write) } }
    }

    /** Read the chosen document and replace the wallet with it. */
    fun importFrom(uri: Uri) {
        viewModelScope.launch { runImport { read -> documentIo.read(uri, read) } }
    }

    /** Reset to Idle once the UI has shown the terminal message. */
    fun acknowledge() {
        _uiState.value = BackupUiState(BackupStatus.Idle)
    }

    /**
     * Checks the snapshot first, then opens the document once and streams it through. Opening for
     * writing truncates the file, so a wallet that restore would refuse must stop before [write] runs;
     * otherwise the backup it was replacing would be lost along with this one.
     */
    internal suspend fun runExport(write: suspend (suspend (OutputStream) -> Unit) -> Unit) {
        if (_uiState.value.status is BackupStatus.Working) return
        _uiState.value = BackupUiState(BackupStatus.Working)
        try {
            val prepared = exportBackup()
            write { output -> prepared.writeTo(output) }
            _uiState.value = BackupUiState(BackupStatus.Success(UiMessage.BackupSaved))
        } catch (error: WalletException.BackupInvalid) {
            // The owner can act on this one: the data to fix is theirs, not the storage's.
            errors.report("Backup export refused by validation", error)
            _uiState.value = BackupUiState(BackupStatus.Error(UiMessage.BackupWalletNeedsFixing))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errors.report("Backup export failed", error)
            _uiState.value = BackupUiState(BackupStatus.Error(UiMessage.BackupSaveFailed))
        }
    }

    /** Opening or reading a document failure stays distinct from a rejected or failed restore. */
    internal suspend fun runImport(read: suspend (suspend (InputStream) -> Unit) -> Unit) {
        if (_uiState.value.status is BackupStatus.Working) return
        _uiState.value = BackupUiState(BackupStatus.Working)
        var restoreCompleted = false
        try {
            read { input ->
                applyRestore(input)
                restoreCompleted = true
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errors.report("Backup document read failed", error)
            // Closing the provider stream must not replace an already completed restore result.
            if (!restoreCompleted) {
                _uiState.value = BackupUiState(BackupStatus.Error(UiMessage.BackupFileUnreadable))
            }
        }
    }

    /** Applies the caller-owned stream and names typed rejections without exposing file contents. */
    internal suspend fun applyRestore(input: InputStream) {
        try {
            restoreBackup(input)
            _uiState.value = BackupUiState(BackupStatus.Success(UiMessage.BackupRestored))
        } catch (error: WalletException.BackupInvalid) {
            _uiState.value = BackupUiState(BackupStatus.Error(UiMessage.BackupNotRecognized))
        } catch (error: WalletException.BackupVersionUnsupported) {
            _uiState.value = BackupUiState(BackupStatus.Error(UiMessage.BackupFromNewerVersion))
        } catch (error: CancellationException) {
            throw error
        } catch (error: java.io.IOException) {
            errors.report("Backup document read failed", error)
            _uiState.value = BackupUiState(BackupStatus.Error(UiMessage.BackupFileUnreadable))
        } catch (error: Exception) {
            errors.report("Backup restore failed", error)
            _uiState.value = BackupUiState(BackupStatus.Error(UiMessage.BackupRestoreFailed))
        }
    }
}
