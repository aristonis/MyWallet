package org.aristonis.mywallet.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.involvesAccount
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.TransactionRepository
import org.aristonis.mywallet.domain.usecase.DeleteAccount
import org.aristonis.mywallet.domain.usecase.SetAccountArchived
import javax.inject.Inject

/** One manageable account: [hasTransactions] drives whether Delete is offered or only Archive. */
data class ManageAccountRow(val account: Account, val hasTransactions: Boolean)

/** Immutable snapshot the Manage Accounts screen renders from. */
data class ManageAccountsUiState(
    val rows: List<ManageAccountRow> = emptyList(),
    val isLoading: Boolean = true,
    val error: UiMessage? = null,
)

/**
 * Manage existing accounts: list ALL of them (archived included, so they can be unarchived here),
 * flag which have transactions, and archive / unarchive / delete. Deleting an account that has
 * transactions is refused by [DeleteAccount] (it would orphan history) — that surfaces as [error],
 * and the screen steers the user to archive instead.
 */
@HiltViewModel
class ManageAccountsViewModel @Inject constructor(
    accounts: AccountRepository,
    transactions: TransactionRepository,
    private val setAccountArchived: SetAccountArchived,
    private val deleteAccount: DeleteAccount,
) : ViewModel() {

    private val _state = MutableStateFlow(ManageAccountsUiState())
    val state: StateFlow<ManageAccountsUiState> = _state.asStateFlow()

    init {
        combine(accounts.observeAll(), transactions.observeAll()) { accountList, transactionList ->
            accountList
                .sortedWith(compareBy({ it.sortOrder }, { it.name }))
                .map { account ->
                    ManageAccountRow(account, hasTransactions = transactionList.any { it.involvesAccount(account.id) })
                }
        }
            .onEach { rows -> _state.update { it.copy(rows = rows, isLoading = false) } }
            .launchIn(viewModelScope)
    }

    fun archive(id: Long) = perform { setAccountArchived(id, archived = true) }
    fun unarchive(id: Long) = perform { setAccountArchived(id, archived = false) }
    fun delete(id: Long) = perform { deleteAccount(id) }

    /** Runs an account action, clearing or surfacing the domain error. Unexpected errors fail loud. */
    private fun perform(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
                _state.update { it.copy(error = null) }
            } catch (e: WalletException) {
                _state.update { it.copy(error = e.toUiMessage()) }
            }
        }
    }
}
