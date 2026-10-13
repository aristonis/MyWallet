package org.aristonis.mywallet.ui.reports

import android.net.Uri
import androidx.annotation.MainThread
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.usecase.ExportTrackingCsv
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.settings.DocumentIo
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

/** CSV preflight finishes before Ready permits a document picker to open. */
sealed interface ReportsExportState {
    data object Idle : ReportsExportState
    data object Preparing : ReportsExportState
    data class Ready(val fileName: String) : ReportsExportState
    data object AwaitingDestination : ReportsExportState
    data object Writing : ReportsExportState
    data object Success : ReportsExportState
    data class Error(val message: UiMessage) : ReportsExportState
}

/** Owns one in-memory export artifact; dates and database changes cannot alter it after preparation. */
internal class TrackingCsvExport(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val health: ObserveLedgerHealth,
    private val export: ExportTrackingCsv,
    private val documents: DocumentIo,
    private val errors: ErrorReporter,
    private val savedState: SavedStateHandle,
) {
    private val mutableState = MutableStateFlow<ReportsExportState>(
        if (savedState.get<Boolean>(PICKER_PENDING_KEY) == true) ReportsExportState.AwaitingDestination
        else ReportsExportState.Idle,
    )
    val state = mutableState.asStateFlow()
    private var artifact: String? = null

    @MainThread
    fun prepare(range: DateRange) {
        if (mutableState.value != ReportsExportState.Idle) return
        mutableState.value = ReportsExportState.Preparing
        scope.launch {
            try {
                artifact = withContext(dispatcher) {
                    health().first()
                    export(range)
                }
                val name = if (range.isAllTime) "tracking-all-time.csv"
                    else "tracking-${range.start}-to-${range.endInclusive}.csv"
                mutableState.value = ReportsExportState.Ready(name)
            } catch (error: CancellationException) {
                clear(ReportsExportState.Idle)
                throw error
            } catch (error: WalletException.MissingRate) {
                clear(ReportsExportState.Error(UiMessage.MissingRate(error.code)))
            } catch (error: WalletException.UnreadableRow) {
                clear(ReportsExportState.Error(UiMessage.DataUnreadable))
            } catch (error: Exception) {
                errors.report("Tracking export preparation failed", error)
                clear(ReportsExportState.Error(UiMessage.Unexpected))
            } catch (error: OutOfMemoryError) {
                // A long all-time range is built in memory. The half-built artifact is garbage once this
                // attempt ends, so the app can keep running and say the export failed.
                errors.report("Tracking export preparation ran out of memory", error)
                clear(ReportsExportState.Error(UiMessage.Unexpected))
            }
        }
    }

    @MainThread
    fun pickerLaunched() {
        if (mutableState.value is ReportsExportState.Ready) {
            savedState[PICKER_PENDING_KEY] = true
            mutableState.value = ReportsExportState.AwaitingDestination
        }
    }

    @MainThread
    fun pickerFailed(error: Exception) {
        if (mutableState.value != ReportsExportState.AwaitingDestination) return
        errors.report("Tracking export document picker failed", error)
        clear(ReportsExportState.Error(UiMessage.Unexpected))
    }

    @MainThread
    fun complete(uri: Uri?) {
        if (mutableState.value != ReportsExportState.AwaitingDestination) return
        if (uri == null) {
            clear(ReportsExportState.Idle)
            return
        }
        val csv = consumeDestination() ?: return
        scope.launch { writeArtifact(csv) { block -> documents.write(uri, block) } }
    }

    /** Marks success only after the document owner has flushed and closed its stream. */
    suspend fun write(open: suspend (suspend (OutputStream) -> Unit) -> Unit): Unit =
        withContext(Dispatchers.Main.immediate) { writeOnMain(open) }

    @MainThread
    private suspend fun writeOnMain(open: suspend (suspend (OutputStream) -> Unit) -> Unit) {
        val csv = consumeDestination() ?: return
        writeArtifact(csv, open)
    }

    /** Only an external picker remains pending; consume its callback before starting any write job. */
    @MainThread
    private fun consumeDestination(): String? {
        if (mutableState.value != ReportsExportState.AwaitingDestination) return null
        savedState[PICKER_PENDING_KEY] = false
        val csv = artifact
        if (csv == null) {
            // Activity results can outlive the process. The marker retains no wallet data,
            // so a lost artifact requires a new export instead of opening an empty document.
            clear(ReportsExportState.Error(UiMessage.ExportInterrupted))
            return null
        }
        artifact = null
        mutableState.value = ReportsExportState.Writing
        return csv
    }

    /**
     * Opening the document has already emptied it, so a write that stopped half-way would leave a
     * short file under a name the owner chose, with nothing said. Once started, the write therefore
     * runs to its end even when the screen goes away; it is one small string. A block that throws
     * cancellation itself still resets to Idle.
     */
    @MainThread
    private suspend fun writeArtifact(csv: String, open: suspend (suspend (OutputStream) -> Unit) -> Unit): Unit =
        withContext(NonCancellable) {
            try {
                open { output ->
                    val writer = output.bufferedWriter(Charsets.UTF_8)
                    writer.write(csv)
                    writer.flush()
                }
                clear(ReportsExportState.Success)
            } catch (error: CancellationException) {
                clear(ReportsExportState.Idle)
                throw error
            } catch (error: Exception) {
                errors.report("Tracking export document write failed", error)
                clear(ReportsExportState.Error(UiMessage.Unexpected))
            }
        }

    @MainThread
    fun consumeResult() {
        if (mutableState.value is ReportsExportState.Error || mutableState.value == ReportsExportState.Success) {
            clear(ReportsExportState.Idle)
        }
    }

    @MainThread
    private fun clear(next: ReportsExportState) {
        savedState[PICKER_PENDING_KEY] = false
        artifact = null
        mutableState.value = next
    }

    private companion object {
        const val PICKER_PENDING_KEY = "tracking_csv_picker_pending"
    }

}
