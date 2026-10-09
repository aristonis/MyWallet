package org.aristonis.mywallet.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.AccountTypeRegistry
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.involvesAccount
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.TransactionRepository
import org.aristonis.mywallet.domain.usecase.UpdateAccount
import javax.inject.Inject

/** Immutable snapshot the edit-account screen renders from. */
data class EditAccountUiState(
    // The account as first loaded; null until [EditAccountViewModel.load] runs, and the screen shows a
    // spinner while it is. Retained so a reused, Activity-scoped view model can be re-pointed at another id.
    val loaded: Account? = null,
    val currencies: List<Currency> = emptyList(),
    val selectedCurrencyCode: String? = null,
    val name: String = "",
    val accountTypeKey: String = AccountTypeRegistry.BuiltIns.CASH.key,
    val openingBalanceInput: String = "",
    // True once the loaded account has transactions: its currency is fixed, so the picker is disabled.
    val currencyLocked: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: UiMessage? = null,
    val saved: Boolean = false,
    /**
     * The stored account could not be read. Not the same as a missing account: the row may still be
     * there, so the screen offers to read it again rather than only to go back.
     */
    val loadFailed: Boolean = false,
) {
    val canSubmit: Boolean
        get() = loaded != null && selectedCurrencyCode != null && name.isNotBlank() && !isSubmitting
}

/**
 * Edit an existing account (name / type / currency / opening balance) via [UpdateAccount]. [load]
 * re-hydrates the form from the stored row and flags [currencyLocked] when the account already has
 * transactions (the domain refuses a currency change there). Because the view model is Activity-scoped
 * (no nav back-stack), [load] cancels any prior fetch and resets identity + one-shot state before the
 * async read, so a re-pointed view model never shows — or lets a Save hit — the previously loaded account.
 *
 * A read that throws (a stored row that cannot be decoded) becomes [EditAccountUiState.loadFailed],
 * reported to [ErrorReporter], instead of crashing the app; [retry] loads the same account again.
 */
@HiltViewModel
class EditAccountViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val currencies: CurrencyRepository,
    private val transactions: TransactionRepository,
    private val updateAccount: UpdateAccount,
    private val moneyParser: MoneyParser,
    private val errors: ErrorReporter,
) : ViewModel() {

    private val _state = MutableStateFlow(EditAccountUiState())
    val state: StateFlow<EditAccountUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    /** The id of the last [load], so [retry] knows which account to read again. */
    private var lastLoadedId: Long? = null

    init {
        currencies.observeAll()
            .onEach { list -> _state.update { it.copy(currencies = list) } }
            .launchIn(viewModelScope)
    }

    /**
     * Load the account with [id] and populate the form. A missing row (deleted since Manage Accounts
     * was shown) surfaces as a fail-loud error rather than an empty editor.
     */
    fun load(id: Long) {
        loadJob?.cancel()
        lastLoadedId = id
        // Reset identity + one-shot state before the async fetch so a reused view model never renders —
        // or lets a Save hit — the previously loaded account while findById runs.
        _state.update {
            it.copy(
                loaded = null,
                currencyLocked = false,
                isSubmitting = false,
                error = null,
                saved = false,
                loadFailed = false,
            )
        }
        loadJob = viewModelScope.launch {
            try {
                readInto(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors.report("Edit account could not read account $id", e)
                _state.update { it.copy(loadFailed = true) }
            }
        }
    }

    /** Reads the last requested account again after a failed read. Before any [load] there is none. */
    fun retry() {
        lastLoadedId?.let(::load)
    }

    private suspend fun readInto(id: Long) {
        val existing = accounts.findById(id)
        // A newer load() cancelled this one; stop here rather than overwrite the fresh state.
        currentCoroutineContext().ensureActive()
        if (existing == null) {
            _state.update { it.copy(error = UiMessage.AccountNotFound) }
            return
        }
        val locked = transactions.observeAll().first().any { it.involvesAccount(id) }
        currentCoroutineContext().ensureActive()
        _state.update { it.populatedFrom(existing, locked) }
    }

    fun selectCurrency(code: String) = _state.update { it.copy(selectedCurrencyCode = code) }

    fun setName(name: String) = _state.update { it.copy(name = name) }

    fun selectAccountType(typeKey: String) = _state.update { it.copy(accountTypeKey = typeKey) }

    fun setOpeningBalance(text: String) = _state.update { it.copy(openingBalanceInput = text) }

    /** Clear the one-shot `saved` signal after the screen has navigated away, so a re-open won't bounce. */
    fun acknowledgeSaved() = _state.update { it.copy(saved = false, error = null) }

    fun submit() {
        val snapshot = _state.value
        val id = snapshot.loaded?.id ?: return // UI guards this via canSubmit
        val currencyCode = snapshot.selectedCurrencyCode ?: return
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            try {
                // Rebuild the opening balance in the selected currency so it matches when the currency changes.
                val openingBalance = moneyParser.parseOpeningBalance(snapshot.openingBalanceInput, currencyCode)
                updateAccount(
                    id = id,
                    name = snapshot.name.trim(),
                    typeKey = snapshot.accountTypeKey,
                    currencyCode = currencyCode,
                    openingBalance = openingBalance,
                )
                _state.update { it.copy(isSubmitting = false, saved = true) }
            } catch (e: WalletException) {
                // Includes AccountCurrencyLocked / AccountNotFound — surface it to the user.
                _state.update { it.copy(isSubmitting = false, error = e.toUiMessage()) }
            } catch (e: IllegalArgumentException) {
                // Blank name (Account invariant) or unparseable amount (NumberFormatException) — surface it.
                _state.update { it.copy(isSubmitting = false, error = e.toUiMessage()) }
            }
        }
    }

    /** Re-hydrate every form field from a stored account, resetting transient/one-shot state. */
    private fun EditAccountUiState.populatedFrom(account: Account, locked: Boolean) = copy(
        loaded = account,
        name = account.name,
        accountTypeKey = account.typeKey,
        selectedCurrencyCode = account.currencyCode,
        openingBalanceInput = moneyParser.toInputString(account.openingBalance.amount),
        currencyLocked = locked,
        isSubmitting = false,
        error = null,
        saved = false,
    )
}
