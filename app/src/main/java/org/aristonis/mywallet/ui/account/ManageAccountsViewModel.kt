package org.aristonis.mywallet.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.usecase.DeleteAccount
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
import org.aristonis.mywallet.domain.usecase.SetAccountArchived
import org.aristonis.mywallet.ui.load.ReadAttempts
import org.aristonis.mywallet.ui.load.toLoadFailure
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage
import javax.inject.Inject

/** One manageable account: [hasTransactions] drives whether Delete is offered or only Archive. */
data class ManageAccountRow(val account: Account, val hasTransactions: Boolean)

/** Immutable snapshot the Manage Accounts screen renders from. */
data class ManageAccountsUiState(
    val rows: List<ManageAccountRow> = emptyList(),
    val isLoading: Boolean = true,
    val error: UiMessage? = null,
    val loadFailed: Boolean = false,
    val loadFailure: UiMessage? = null,
)

/**
 * Manage existing accounts: list ALL of them (archived included, so they can be unarchived here),
 * flag which have transactions, and archive / unarchive / delete. Deleting an account that has
 * transactions is refused by [DeleteAccount] (it would orphan history) — that surfaces as [error],
 * and the screen steers the user to archive instead.
 *
 * The flag comes from [LedgerQueries.observeAccountsInUse], the same answer the delete guard gives,
 * so the screen never offers a delete the guard would refuse and never loads an entry to decide it.
 */
@HiltViewModel
class ManageAccountsViewModel @Inject constructor(
    accounts: AccountRepository,
    ledger: LedgerQueries,
    private val setAccountArchived: SetAccountArchived,
    private val deleteAccount: DeleteAccount,
    private val observeLedgerHealth: ObserveLedgerHealth,
    private val errors: ErrorReporter,
) : ViewModel() {

    private val _state = MutableStateFlow(ManageAccountsUiState())
    val state: StateFlow<ManageAccountsUiState> = _state.asStateFlow()

    private val attempts = ReadAttempts()

    init {
        attempts.reading(loading = { ManageAccountsUiState() }) {
            combine(accounts.observeAll(), ledger.observeAccountsInUse(), observeLedgerHealth()) {
                accountList, inUse, _ ->
                val rows = accountList
                    .sortedWith(compareBy({ it.sortOrder }, { it.name }))
                    .map { account -> ManageAccountRow(account, hasTransactions = account.id in inUse) }
                ManageAccountsUiState(rows = rows, isLoading = false)
            }.catch { failure ->
                if (failure is CancellationException) throw failure
                errors.report("Manage accounts could not read the wallet", failure)
                emit(ManageAccountsUiState(isLoading = false, loadFailed = true,
                    loadFailure = failure.toLoadFailure()))
            }
        }.onEach { read ->
            // Every write re-emits the list. An action's error stays until the next action, so the
            // owner can still read why Delete was refused; only a retry's fresh start drops it.
            _state.update { current -> if (read.isLoading) read else read.copy(error = current.error) }
        }.launchIn(viewModelScope)
    }

    /** Reads the ledger again before offering account actions after a failed read. */
    fun retry() = attempts.retry()

    fun archive(id: Long) = perform { setAccountArchived(id, archived = true) }
    fun unarchive(id: Long) = perform { setAccountArchived(id, archived = false) }
    fun delete(id: Long) = perform { deleteAccount(id) }

    /** Runs an account action, clearing or surfacing the domain error. Unexpected errors fail loud. */
    private fun perform(action: suspend () -> Unit) {
        if (_state.value.isLoading || _state.value.loadFailed) return
        viewModelScope.launch {
            try {
                // Recheck on the action path so a write cannot outrun a failed list read.
                observeLedgerHealth().first()
                action()
                _state.update { it.copy(error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WalletException) {
                _state.update { it.copy(error = e.toUiMessage()) }
            } catch (e: Exception) {
                errors.report("Manage accounts could not perform the action", e)
                _state.update { it.copy(error = UiMessage.Unexpected) }
            }
        }
    }
}
