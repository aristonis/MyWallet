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
import kotlinx.coroutines.withContext
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.di.DefaultDispatcher
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtDetail
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.usecase.ObserveDebt
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
import org.aristonis.mywallet.domain.usecase.RenameDebt
import org.aristonis.mywallet.domain.usecase.UpdateDebtDetails
import org.aristonis.mywallet.ui.format.display
import org.aristonis.mywallet.ui.load.ReadAttempts
import org.aristonis.mywallet.ui.load.toLoadFailure
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage
import org.aristonis.mywallet.ui.transaction.movedAmount
import java.time.LocalDate
import javax.inject.Inject

data class DebtDetailUiState(
    val detail: DebtDetail? = null,
    val counterpartyInput: String = "",
    val dueDate: LocalDate? = null,
    val noteInput: String = "",
    val remainingDisplay: String = "",
    val historyAmounts: Map<Long, String> = emptyMap(),
    val error: UiMessage? = null,
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val loadError: UiMessage? = null,
    val isSubmitting: Boolean = false,
)

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
) : ViewModel() {
    private val mutableState = MutableStateFlow(DebtDetailUiState())
    val state: StateFlow<DebtDetailUiState> = mutableState.asStateFlow()
    private val attempts = ReadAttempts()
    private var loadedId: Long? = null
    private var reads: Job? = null
    private var persistedDebt: Debt? = null
    private var actionError: UiMessage? = null

    fun load(debtId: Long) {
        if (loadedId == debtId) return
        reads?.cancel()
        loadedId = debtId
        persistedDebt = null
        actionError = null
        mutableState.value = DebtDetailUiState()
        reads = viewModelScope.launch {
            attempts.reading(loading = { state.value.copy(isLoading = true, loadFailed = false, loadError = null) }) {
                combine(observeDebt(debtId), observeLedgerHealth(), currencies.observeAll()) { detail, _, codes ->
                    DebtDetailUiState(detail = detail, isLoading = false,
                        remainingDisplay = detail?.let { moneyFormatter.display(it.summary.remaining, codes) }.orEmpty(),
                        historyAmounts = detail?.entries.orEmpty().associate { it.id to moneyFormatter.display(it.movedAmount(), codes) },
                        error = if (detail == null) UiMessage.DebtNotFound else null)
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
            val refreshName = next != null && (previous == null || current.counterpartyInput == previous.counterparty)
            val refreshDate = next != null && (previous == null || current.dueDate == previous.dueDate)
            val refreshNote = next != null && (previous == null || current.noteInput == previous.note.orEmpty())
            current.copy(detail = read.detail, remainingDisplay = read.remainingDisplay, historyAmounts = read.historyAmounts,
                isLoading = read.isLoading, loadFailed = read.loadFailed, loadError = read.loadError,
                counterpartyInput = if (refreshName) next!!.counterparty else current.counterpartyInput,
                dueDate = if (refreshDate) next!!.dueDate else current.dueDate,
                noteInput = if (refreshNote) next!!.note.orEmpty() else current.noteInput,
                error = read.error ?: actionError)
        }
        if (next != null) persistedDebt = next
    }

    fun setCounterparty(value: String) {
        actionError = null
        mutableState.update { it.copy(counterpartyInput = value, error = null) }
    }

    fun setDueDate(value: LocalDate?) { mutableState.update { it.copy(dueDate = value) } }
    fun setNote(value: String) { mutableState.update { it.copy(noteInput = value) } }
    fun rename() {
        val draft = state.value
        submit { id -> renameDebt(id, draft.counterpartyInput) }
    }
    fun saveDetails() {
        val draft = state.value
        submit { id -> updateDebtDetails(id, draft.dueDate, draft.noteInput.takeIf(String::isNotBlank)) }
    }

    private fun submit(write: suspend (Long) -> Unit) {
        val id = loadedId ?: return
        if (state.value.isSubmitting || state.value.loadFailed || state.value.detail == null) return
        actionError = null
        mutableState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            try {
                withContext(defaultDispatcher) { write(id) }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                errors.report("Debt details could not be saved", failure)
                actionError = failure.toUiMessage()
                mutableState.update { it.copy(error = actionError) }
            } finally {
                mutableState.update { it.copy(isSubmitting = false) }
            }
        }
    }

    fun retry() = attempts.retry()
    fun refreshToday() = today.refresh()
}
