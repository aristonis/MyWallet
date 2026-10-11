package org.aristonis.mywallet.ui.debt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.CategoryRole
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CategoryRoleRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.usecase.DeleteDebt
import org.aristonis.mywallet.domain.usecase.PreviewDebtDeletion
import org.aristonis.mywallet.domain.usecase.RestoreEntries
import org.aristonis.mywallet.domain.usecase.WriteOffDebt
import org.aristonis.mywallet.ui.format.display
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage
import java.time.LocalDate

/** Runs detail actions through one submission lock and discards responses from a previous debt. */
internal class DebtDetailActions(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val state: MutableStateFlow<DebtDetailUiState>,
    private val loadedId: () -> Long?,
    private val categories: CategoryRepository,
    private val roles: CategoryRoleRepository,
    private val currencies: CurrencyRepository,
    private val formatter: MoneyFormatter,
    private val today: TodaySource,
    private val writeOff: WriteOffDebt,
    private val previewDeletion: PreviewDebtDeletion,
    private val delete: DeleteDebt,
    private val restore: RestoreEntries,
    private val errors: ErrorReporter,
    private val onError: (UiMessage?) -> Unit,
) {
    private var generation = 0L
    private var job: Job? = null

    fun reset() {
        generation++
        job?.cancel()
        job = null
    }

    fun canSaveMetadata(): Boolean = canStart() && !hasDialog() && state.value.undoableDelete == null

    fun saveMetadata(write: suspend (Long) -> Unit) {
        if (!canSaveMetadata()) return
        launchAction(write, onSuccess = {})
    }

    fun openWriteOff() {
        val detail = state.value.detail ?: return
        if (!canSaveMetadata() || !detail.summary.remaining.isPositive) return
        val remainingDisplay = state.value.remainingDisplay
        launchAction(write = {
            val role = if (detail.summary.debt.side == org.aristonis.mywallet.domain.model.DebtSide.I_OWE) {
                CategoryRole.DEBT_FORGIVEN
            } else {
                CategoryRole.LOST_DEBT
            }
            val bound = roles.categoryFor(role)
            val choices = categories.observeAll().first().filter { it.kind == role.kind && !it.isSubCategory }
            WriteOffDialogUiState(choices, bound?.takeIf { id -> choices.any { it.id == id } },
                today.now(), remainingDisplay)
        }, onSuccess = { dialog -> state.update { it.copy(writeOffDialog = dialog) } })
    }

    fun selectWriteOffCategory(id: Long) {
        if (state.value.isSubmitting) return
        val dialog = state.value.writeOffDialog ?: return
        if (dialog.categories.none { it.id == id }) return
        state.update { it.copy(writeOffDialog = dialog.copy(selectedCategoryId = id, error = null)) }
    }

    fun setWriteOffDate(date: LocalDate) {
        if (state.value.isSubmitting) return
        state.update { it.copy(writeOffDialog = it.writeOffDialog?.copy(date = date)) }
    }

    fun dismissWriteOff() {
        if (state.value.isSubmitting) return
        state.update { it.copy(writeOffDialog = null) }
    }

    fun confirmWriteOff() {
        val dialog = state.value.writeOffDialog ?: return
        if (!canStart()) return
        val categoryId = dialog.selectedCategoryId
        if (categoryId == null) {
            state.update { it.copy(writeOffDialog = dialog.copy(error = UiMessage.CategoryRequired)) }
            return
        }
        launchAction(write = { id -> writeOff(id, categoryId, dialog.date) },
            onSuccess = { state.update { it.copy(writeOffDialog = null) } },
            onFailure = { error -> state.update { it.copy(writeOffDialog = it.writeOffDialog?.copy(error = error)) } })
    }

    fun openDeleteDebt() {
        if (!canSaveMetadata()) return
        launchAction(write = { id ->
            val preview = previewDeletion(id)
            DeleteDebtDialogUiState(preview, formatter.display(preview.remainingAfter, currencies.observeAll().first()))
        }, onSuccess = { dialog -> state.update { it.copy(deleteDialog = dialog) } })
    }

    fun dismissDeleteDebt() {
        if (state.value.isSubmitting) return
        state.update { it.copy(deleteDialog = null) }
    }

    fun confirmDeleteDebt() {
        if (!canStart() || state.value.deleteDialog == null) return
        launchAction(write = { id -> delete(id) }, onSuccess = { removed ->
            state.update { it.copy(deleteDialog = null, undoableDelete = removed) }
        }, onFailure = { error -> state.update { it.copy(deleteDialog = it.deleteDialog?.copy(error = error)) } })
    }

    fun undoDeleteDebt() {
        val bundle = state.value.undoableDelete ?: return
        if (!canStart()) return
        launchAction(write = { restore(bundle) }, onSuccess = {
            state.update { it.copy(undoableDelete = null, deleted = false) }
        })
    }

    fun confirmDeleted() {
        if (state.value.isSubmitting || state.value.undoableDelete == null) return
        state.update { it.copy(undoableDelete = null, deleted = true) }
    }

    private fun canStart(): Boolean = state.value.let {
        loadedId() != null && it.detail != null && !it.loadFailed && !it.isSubmitting && !it.deleted
    }

    private fun hasDialog(): Boolean = state.value.writeOffDialog != null || state.value.deleteDialog != null

    private fun <T> launchAction(write: suspend (Long) -> T, onSuccess: (T) -> Unit,
        onFailure: (UiMessage) -> Unit = {}) {
        val id = loadedId() ?: return
        if (!canStart()) return
        val requested = generation
        onError(null)
        state.update { it.copy(isSubmitting = true, error = null) }
        job = scope.launch {
            try {
                val result = withContext(dispatcher) { write(id) }
                ensureActive()
                if (requested == generation) onSuccess(result)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                if (requested == generation) {
                    // Typed refusals can contain names and money; diagnostics retain only their kind.
                    val diagnostic = if (failure is WalletException) {
                        IllegalStateException(failure.javaClass.simpleName)
                    } else failure
                    errors.report("Debt action failed", diagnostic)
                    val message = failure.toUiMessage()
                    onError(message)
                    state.update { it.copy(error = message) }
                    onFailure(message)
                }
            } finally {
                if (requested == generation) state.update { it.copy(isSubmitting = false) }
            }
        }
    }
}
