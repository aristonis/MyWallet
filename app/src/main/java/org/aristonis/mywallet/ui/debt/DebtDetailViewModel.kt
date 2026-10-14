package org.aristonis.mywallet.ui.debt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.di.DefaultDispatcher
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtDetail
import org.aristonis.mywallet.domain.model.UndoBundle
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CategoryRoleRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.usecase.DeleteDebt
import org.aristonis.mywallet.domain.usecase.ObserveDebt
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
import org.aristonis.mywallet.domain.usecase.PreviewDebtDeletion
import org.aristonis.mywallet.domain.usecase.RenameDebt
import org.aristonis.mywallet.domain.usecase.RestoreEntries
import org.aristonis.mywallet.domain.usecase.UpdateDebtDetails
import org.aristonis.mywallet.domain.usecase.WriteOffDebt
import org.aristonis.mywallet.ui.format.display
import org.aristonis.mywallet.ui.load.ReadAttempts
import org.aristonis.mywallet.ui.load.toLoadFailure
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage
import org.aristonis.mywallet.ui.transaction.movedAmount
import java.time.LocalDate
import javax.inject.Inject

/** What the debt detail screen shows: the debt, the draft fields being edited, and the state of the read. */
data class DebtDetailUiState(
    val detail: DebtDetail? = null,
    val counterpartyInput: String = "",
    val dueDate: LocalDate? = null,
    val noteInput: String = "",
    val originalDisplay: String = "",
    val repaidDisplay: String = "",
    val writtenOffDisplay: String = "",
    val remainingDisplay: String = "",
    val historyAmounts: Map<Long, String> = emptyMap(),
    val error: UiMessage? = null,
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val loadError: UiMessage? = null,
    val isSubmitting: Boolean = false,
    val writeOffDialog: WriteOffDialogUiState? = null,
    val deleteDialog: DeleteDebtDialogUiState? = null,
    val undoableDelete: UndoBundle? = null,
    val deleted: Boolean = false,
) {
    /** A restore was refused: the undo snackbar is the one place that says so, and it offers a retry. */
    val undoFailed: Boolean get() = undoableDelete != null && error != null

    /** The error the page body shows; name errors sit on the name field and a failed undo uses the snackbar. */
    val inlineError: UiMessage? get() = error?.takeUnless { undoFailed || it.isNameError() }
}

internal fun UiMessage.isNameError(): Boolean = this is UiMessage.DebtNameTaken || this == UiMessage.NameBlank

/** Reads one debt and serializes metadata edits, write-offs, deletion and recoverable undo. */
@HiltViewModel
class DebtDetailViewModel @Inject constructor(
    private val observeDebt: ObserveDebt,
    private val renameDebt: RenameDebt,
    private val updateDebtDetails: UpdateDebtDetails,
    private val currencies: CurrencyRepository,
    private val moneyFormatter: MoneyFormatter,
    private val today: TodaySource,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    private val errors: ErrorReporter,
    private val observeLedgerHealth: ObserveLedgerHealth,
    categories: CategoryRepository,
    roles: CategoryRoleRepository,
    writeOffDebt: WriteOffDebt,
    previewDebtDeletion: PreviewDebtDeletion,
    deleteDebt: DeleteDebt,
    restoreEntries: RestoreEntries,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DebtDetailUiState())
    val state: StateFlow<DebtDetailUiState> = mutableState.asStateFlow()
    private val attempts = ReadAttempts()
    private var loadedId: Long? = null
    private var reads: Job? = null
    private var persistedDebt: Debt? = null
    private var actionError: UiMessage? = null
    private val actions = DebtDetailActions(viewModelScope, defaultDispatcher, mutableState, { loadedId },
        categories, roles, currencies, moneyFormatter, today, writeOffDebt, previewDebtDeletion, deleteDebt,
        restoreEntries, errors, { actionError = it })

    /** Starts reading [debtId]; asking again for the debt already loaded does nothing. */
    fun load(debtId: Long) {
        if (loadedId == debtId) return
        reads?.cancel()
        actions.reset()
        loadedId = debtId
        persistedDebt = null
        actionError = null
        mutableState.value = DebtDetailUiState()
        reads = viewModelScope.launch {
            attempts.reading(loading = { state.value.copy(isLoading = true, loadFailed = false, loadError = null) }) {
                combine(observeDebt(debtId), observeLedgerHealth(), currencies.observeAll()) { detail, _, codes ->
                    DebtDetailUiState(
                        detail = detail,
                        isLoading = false,
                        originalDisplay = detail
                            ?.let { moneyFormatter.display(it.amounts.original, codes) }.orEmpty(),
                        repaidDisplay = detail
                            ?.let { moneyFormatter.display(it.amounts.repaid, codes) }.orEmpty(),
                        writtenOffDisplay = detail
                            ?.let { moneyFormatter.display(it.amounts.writtenOff, codes) }.orEmpty(),
                        remainingDisplay = detail
                            ?.let { moneyFormatter.display(it.summary.remaining, codes) }.orEmpty(),
                        historyAmounts = detail?.entries.orEmpty()
                            .associate { it.id to moneyFormatter.display(it.movedAmount(), codes) },
                        error = if (detail == null) UiMessage.DebtNotFound else null,
                    )
                }.catch { failure ->
                    if (failure is CancellationException) throw failure
                    errors.report("Debt detail could not read the wallet", failure)
                    emit(state.value.copy(isLoading = false, loadFailed = true, loadError = failure.toLoadFailure()))
                }
            }.flowOn(defaultDispatcher).collect { read -> acceptRead(read) }
        }
    }

    private fun acceptRead(read: DebtDetailUiState) {
        val previous = persistedDebt
        val next = read.detail?.summary?.debt
        mutableState.update { current ->
            val withRead = current.copy(detail = read.detail, originalDisplay = read.originalDisplay,
                repaidDisplay = read.repaidDisplay, writtenOffDisplay = read.writtenOffDisplay,
                remainingDisplay = read.remainingDisplay,
                historyAmounts = read.historyAmounts, isLoading = read.isLoading, loadFailed = read.loadFailed,
                loadError = read.loadError, error = read.error ?: actionError)
            if (next == null) withRead else withRead.refreshedUnlessEdited(previous, next)
        }
        if (next != null) persistedDebt = next
    }

    /** The draft fields take the stored values, except one the person already changed from [previous]. */
    private fun DebtDetailUiState.refreshedUnlessEdited(previous: Debt?, next: Debt) = copy(
        counterpartyInput = if (previous == null || counterpartyInput == previous.counterparty) {
            next.counterparty
        } else {
            counterpartyInput
        },
        dueDate = if (previous == null || dueDate == previous.dueDate) next.dueDate else dueDate,
        noteInput = if (previous == null || noteInput == previous.note.orEmpty()) next.note.orEmpty() else noteInput,
    )

    fun setCounterparty(value: String) {
        actionError = null
        mutableState.update { it.copy(counterpartyInput = value, error = null) }
    }

    fun setDueDate(value: LocalDate?) {
        mutableState.update { it.copy(dueDate = value) }
    }

    fun setNote(value: String) {
        mutableState.update { it.copy(noteInput = value) }
    }

    /** Saves the typed name; a name already used on this side and currency is reported inline. */
    fun rename() {
        val draft = state.value
        submit { id -> renameDebt(id, draft.counterpartyInput) }
    }

    /** Saves the due date and the note; a blank note is stored as none. */
    fun saveDetails() {
        val draft = state.value
        submit { id -> updateDebtDetails(id, draft.dueDate, draft.noteInput.takeIf(String::isNotBlank)) }
    }

    private fun submit(write: suspend (Long) -> Unit) = actions.saveMetadata(write)

    /** Opens loss or forgiveness with a valid bound category, or requires the person to choose one. */
    fun openWriteOff() = actions.openWriteOff()
    /** Chooses one of the top-level categories offered by the write-off dialog. */
    fun selectWriteOffCategory(id: Long) = actions.selectWriteOffCategory(id)
    /** Changes the write-off date while the dialog is idle. */
    fun setWriteOffDate(date: LocalDate) = actions.setWriteOffDate(date)
    /** Writes off the remaining amount read inside the transaction, using the chosen category and date. */
    fun confirmWriteOff() = actions.confirmWriteOff()
    /** Closes the idle write-off dialog. */
    fun dismissWriteOff() = actions.dismissWriteOff()
    /** Reads a deletion preview before asking for confirmation. */
    fun openDeleteDebt() = actions.openDeleteDebt()
    /** Deletes current debt history by id and keeps the result available for undo. */
    fun confirmDeleteDebt() = actions.confirmDeleteDebt()
    /** Closes the idle deletion preview. */
    fun dismissDeleteDebt() = actions.dismissDeleteDebt()
    /** Restores the delete bundle; a refusal keeps it available for another attempt. */
    fun undoDeleteDebt() = actions.undoDeleteDebt()
    /** Finishes a deletion after its undo opportunity closes. */
    fun confirmDeleted() = actions.confirmDeleted()

    fun retry() = attempts.retry()

    /** Reopens the midnight timer with the current device zone. */
    fun refreshToday() = today.refresh()

    /** Today from the injected clock, read when the due-date picker opens. */
    fun today(): LocalDate = today.now()
}
