package org.aristonis.mywallet.ui.deduction

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DeductionItem
import org.aristonis.mywallet.domain.usecase.DeleteDeductionItem
import org.aristonis.mywallet.domain.usecase.ObserveDeductionItems
import org.aristonis.mywallet.domain.usecase.SetDeductionItemEnabled
import org.aristonis.mywallet.ui.load.ReadAttempts
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage
import javax.inject.Inject

/** One rule with its value already formatted for the reader's locale. */
data class DeductionListRow(val item: DeductionItem, val valueText: String)

/** The reusable rules and any pending offer to disable a rule that history still uses. */
data class DeductionsUiState(
    val items: List<DeductionListRow> = emptyList(),
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val isWorking: Boolean = false,
    val error: UiMessage? = null,
    val disableOfferedId: Long? = null,
) {
    val showAddAction: Boolean get() = !isLoading && !loadFailed
}

/** Maintains reusable rules while preserving every saved income snapshot. */
@HiltViewModel
class DeductionsViewModel @Inject constructor(
    observeItems: ObserveDeductionItems,
    private val deleteItem: DeleteDeductionItem,
    private val setEnabled: SetDeductionItemEnabled,
    private val parser: MoneyParser,
    private val errors: ErrorReporter,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DeductionsUiState())
    val state = mutableState.asStateFlow()
    private val attempts = ReadAttempts()

    init {
        attempts.reading(loading = { DeductionsUiState() }) {
            observeItems().map { rules -> DeductionsUiState(items = rules.map(::row), isLoading = false) }
                .catch { error ->
                    if (error is CancellationException) throw error
                    errors.report("Deduction rules could not be read", error)
                    emit(DeductionsUiState(isLoading = false, loadFailed = true))
                }
        }.onEach { read ->
            mutableState.update { current ->
                read.copy(isWorking = current.isWorking, error = current.error,
                    disableOfferedId = current.disableOfferedId)
            }
        }.launchIn(viewModelScope)
    }

    // The input formatter keeps every stored digit and only swaps the decimal separator, so the list
    // shows the exact rule value the editor will open with.
    private fun row(item: DeductionItem) = DeductionListRow(item, parser.toInputString(item.value))

    /** Retries a failed rules read. */
    fun retry() = attempts.retry()

    /** Deletes an unused rule or offers to switch a used rule off. */
    fun delete(id: Long) = perform { deleteItem(id) }

    /** Changes whether future incomes can select this rule. */
    fun setEnabled(id: Long, enabled: Boolean) = perform { setEnabled.invoke(id, enabled) }

    /** Accepts the offer to disable the rule that could not be deleted. */
    fun disableUsedItem() {
        val id = state.value.disableOfferedId ?: return
        perform { setEnabled.invoke(id, false) }
    }

    /** Dismisses the deletion refusal without changing the rule. */
    fun dismissDisableOffer() = mutableState.update { it.copy(disableOfferedId = null) }

    /** Clears a message after it has been shown. */
    fun acknowledge() = mutableState.update { it.copy(error = null) }

    private fun perform(action: suspend () -> Unit) {
        if (state.value.isWorking || state.value.loadFailed || state.value.isLoading) return
        mutableState.update { it.copy(isWorking = true, error = null) }
        viewModelScope.launch {
            try {
                action()
                mutableState.update { it.copy(disableOfferedId = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: WalletException.DeductionItemInUse) {
                mutableState.update { it.copy(disableOfferedId = error.id) }
            } catch (error: Exception) {
                if (error !is WalletException) errors.report("Deduction rule could not be changed", error)
                mutableState.update { it.copy(error = error.toUiMessage()) }
            } finally {
                mutableState.update { it.copy(isWorking = false) }
            }
        }
    }
}
