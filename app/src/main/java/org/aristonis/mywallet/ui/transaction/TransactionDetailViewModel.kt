package org.aristonis.mywallet.ui.transaction

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.di.DefaultDispatcher
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.DebtRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.usecase.DeleteEntry
import org.aristonis.mywallet.domain.usecase.RestoreEntries
import org.aristonis.mywallet.ui.load.ReadAttempts
import org.aristonis.mywallet.ui.load.toLoadFailure
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage
import org.aristonis.mywallet.ui.navigation.ARG_ID
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** One answer of the entry read, before it is folded into the page state. */
private sealed interface DetailRead {
    data object Loading : DetailRead
    data class Found(val row: TransactionRow) : DetailRead
    data object Missing : DetailRead
    data class Failed(val failure: UiMessage) : DetailRead
}

/**
 * Reads one saved entry for the read-only transaction page and deletes it with the editor's rules:
 * [DeleteEntry] refuses a delete that would push a debt below zero, and [undoDelete] puts the
 * removed entry back through [RestoreEntries]. The entry id arrives in [savedStateHandle] under the
 * route's id argument.
 *
 * The row is built with the list's own mapping and lookups, so the page states exactly what the
 * list row it was opened from states. The lookups are live: renaming the account while the page is
 * open renames it here too. The entry itself is a one-shot read, so [refresh] reads it again when
 * the page comes back from the editor. Building runs on the default dispatcher, off the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class TransactionDetailViewModel @Inject constructor(
    accounts: AccountRepository,
    categories: CategoryRepository,
    currencies: CurrencyRepository,
    settings: SettingsRepository,
    debts: DebtRepository,
    private val entries: EntryRepository,
    private val deleteEntry: DeleteEntry,
    private val restoreEntries: RestoreEntries,
    private val moneyFormatter: MoneyFormatter,
    savedStateHandle: SavedStateHandle,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    private val errors: ErrorReporter,
) : ViewModel() {

    private val entryId: Long = checkNotNull(savedStateHandle.get<Long>(ARG_ID)) {
        "transaction page opened with no $ARG_ID argument"
    }

    // A plain state flow rather than stateIn: Retry has to show loading the moment it is tapped,
    // before the new read is even scheduled.
    private val _state = MutableStateFlow(TransactionDetailUiState())
    val state: StateFlow<TransactionDetailUiState> = _state.asStateFlow()

    private val attempts = ReadAttempts()

    /** Bumped to read the entry again without showing loading over the details already shown. */
    private val refreshes = MutableStateFlow(0)

    /** The same names, currencies and base currency the transactions list dresses its rows in. */
    private val lookups: Flow<RowLookups> =
        combine(
            accounts.observeAll(),
            categories.observeAll(),
            currencies.observeAll(),
            settings.observeOrNull(),
            debts.observeAll(),
        ) { accountList, categoryList, currencyList, currentSettings, debtList ->
            RowLookups(
                accountsById = accountList.associateBy { it.id },
                categoriesById = categoryList.associateBy { it.id },
                currencies = currencyList,
                baseCurrencyCode = currentSettings?.baseCurrencyCode,
                debtNamesById = debtList.associate { it.id to it.counterparty },
            )
        }

    init {
        attempts.reading(loading = { DetailRead.Loading }, read = ::readEntry)
            .onEach { read -> show(read) }
            .launchIn(viewModelScope)
    }

    /**
     * One attempt: the entry, dressed in the current lookups. Any failure (a stored row the app
     * cannot decode, lookups that cannot be read, a row the formatter cannot handle) is reported once
     * and ends this attempt as a failed value; [retry] starts a new one.
     */
    private fun readEntry(): Flow<DetailRead> =
        combine(lookups, refreshes) { current, _ -> current }
            .mapLatest { current -> readRow(current) }
            .catch { error ->
                if (error is CancellationException) throw error
                errors.report("Transaction $entryId could not be read", error)
                emit(DetailRead.Failed(error.toLoadFailure()))
            }
            .flowOn(defaultDispatcher)

    private suspend fun readRow(lookups: RowLookups): DetailRead {
        val entry = entries.findById(entryId) ?: return DetailRead.Missing
        return DetailRead.Found(entry.toRow(lookups, moneyFormatter))
    }

    private fun show(read: DetailRead) = _state.update { current ->
        // From the moment a delete starts the entry is meant to be gone: a re-read would only find
        // it missing and wipe the details the undo window still describes.
        if (current.isDeleting || current.undoableDelete != null || current.deleted) return@update current
        when (read) {
            DetailRead.Loading -> current.copy(isLoading = true, row = null, notFound = false, loadFailure = null)
            is DetailRead.Found -> current.copy(isLoading = false, row = read.row, notFound = false, loadFailure = null)
            // A page that showed the entry and now finds it gone was deleted in the editor on top of
            // it: the page closes instead of reporting an entry that never existed.
            DetailRead.Missing -> if (current.row != null) {
                current.copy(isLoading = false, row = null, notFound = false, loadFailure = null, deleted = true)
            } else {
                current.copy(isLoading = false, row = null, notFound = true, loadFailure = null)
            }
            is DetailRead.Failed ->
                current.copy(isLoading = false, row = null, notFound = false, loadFailure = read.failure)
        }
    }

    /** Reads the entry and the lookups again after a failed read, showing loading until it answers. */
    fun retry() {
        if (_state.value.loadFailure == null) return
        _state.update { it.copy(isLoading = true, row = null, notFound = false, loadFailure = null) }
        attempts.retry()
    }

    /**
     * Reads the entry again in place, for a page coming back from the editor: the edit may have
     * changed it, or deleted it. Nothing changes on screen until the new read answers.
     */
    fun refresh() {
        refreshes.update { it + 1 }
    }

    /**
     * Removes the shown entry, then holds what was removed so [undoDelete] can put it back; the page
     * stays until [confirmDelete]. Does nothing while the entry is loading, missing or unreadable,
     * and nothing while a delete or undo runs: `isDeleting` is set before the write is launched, so
     * two taps in a row cannot both get through. A refusal (a debt pushed below zero) leaves the
     * entry where it was and says why.
     */
    fun delete() {
        if (!_state.value.canAct) return
        _state.update { it.copy(isDeleting = true, deleteError = null) }
        viewModelScope.launch {
            try {
                val removed = deleteEntry(entryId)
                _state.update { it.copy(isDeleting = false, undoableDelete = removed) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.toUiMessage()
                if (message == UiMessage.Unexpected) errors.report("Transaction $entryId could not be deleted", e)
                _state.update { it.copy(isDeleting = false, deleteError = message) }
            }
        }
    }

    /**
     * Puts the removed entry back at its own id. The page stays open on it, since the entry is there
     * again. The undoable bundle is dropped only once the restore succeeded, so a failed restore can
     * be tried again.
     *
     * [WalletException.EntryIdTaken] means the entry is already back (an earlier undo got there
     * first): the undo is done, not failed. Restore never overwrites, so whatever holds the id stays.
     */
    fun undoDelete() {
        val current = _state.value
        val removed = current.undoableDelete ?: return
        if (current.isDeleting) return
        _state.update { it.copy(isDeleting = true, deleteError = null) }
        viewModelScope.launch {
            try {
                restoreEntries(removed)
                _state.update { it.copy(undoableDelete = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WalletException.EntryIdTaken) {
                _state.update { it.copy(undoableDelete = null) }
            } catch (e: WalletException) {
                // A named refusal, such as the account being deleted since: the owner is told why.
                _state.update { it.copy(deleteError = e.toUiMessage()) }
            } catch (e: Exception) {
                errors.report("Transaction $entryId could not be restored", e)
                _state.update { it.copy(deleteError = UiMessage.TransactionRestoreFailed) }
            } finally {
                _state.update { it.copy(isDeleting = false) }
            }
        }
    }

    /**
     * No undo was taken: the delete stands and the page can close. The entry was already removed by
     * [delete]; nothing more is written. Ignored while an undo is being written, which would
     * otherwise close the page on an entry that is coming back.
     */
    fun confirmDelete() = _state.update {
        if (it.undoableDelete == null || it.isDeleting) it else it.copy(undoableDelete = null, deleted = true)
    }

    /** Clears the one-shot [TransactionDetailUiState.deleted] signal once the page has closed. */
    fun acknowledgeDeleted() = _state.update { it.copy(deleted = false) }
}
