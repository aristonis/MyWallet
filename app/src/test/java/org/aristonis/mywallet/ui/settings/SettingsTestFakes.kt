package org.aristonis.mywallet.ui.settings

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.port.BackupRepository
import org.aristonis.mywallet.domain.port.SettingsRepository

// App-side fakes: the domain module's own fakes aren't on the :app test classpath, so the ports are
// re-faked here. `restoreBackup` is configurable to succeed or throw a typed rejection.
internal class FakeBackupRepository(
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

internal class FakeSettingsRepository(initial: Settings?) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override fun observe(): Flow<Settings> = state.filterNotNull()
    override fun observeOrNull(): Flow<Settings?> = state
    override suspend fun get(): Settings = state.value ?: error("settings not initialized")
    override suspend fun save(settings: Settings) { state.value = settings }
}

// The tested cores take the IO step as a lambda, so this is only here to construct the view-model —
// its Uri-typed methods are never invoked in these tests, so no real android.net.Uri is built.
internal class FakeDocumentIo : DocumentIo {
    override suspend fun writeText(uri: Uri, text: String) = Unit
    override suspend fun readText(uri: Uri): String = "{}"
}
