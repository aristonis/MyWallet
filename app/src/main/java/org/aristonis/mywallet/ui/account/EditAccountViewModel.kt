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
import org.aristonis.mywallet.ui.load.toLoadFailure
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.AccountTypeRegistry
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
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
    val creditLimitInput: String = "",
    val feeMode: FeeMode? = null,
    val feeValueInput: String = "",
    // True once the loaded account has transactions: its currency is fixed, so the picker is disabled.
    val currencyLocked: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: UiMessage? = null,
    val errorField: AccountInputField? = null,
    val saved: Boolean = false,
    /**
     * The stored account could not be read. Not the same as a missing account: the row may still be
     * there, so the screen offers to read it again rather than only to go back.
     */
    val loadFailed: Boolean = false,
    /**
     * What to tell the user while [loadFailed]: [UiMessage.DataUnreadable] for a stored row the app
     * cannot read, [UiMessage.Unexpected] for any other failure. Null while nothing failed.
     */
    val loadFailure: UiMessage? = null,
) {
    val canSubmit: Boolean
        get() = loaded != null && selectedCurrencyCode != null && name.isNotBlank() && !isSubmitting

    /** The limit field is offered only for a type whose negative balance is money owed. */
    val showsCreditLimit: Boolean
        get() = isOwedWhenNegative(accountTypeKey)
}

/**
 * Edit an existing account (name / type / currency / opening balance / credit limit) via [UpdateAccount]. [load]
 * re-hydrates the form from the stored row and flags [currencyLocked] when the account already has
 * transactions (the domain refuses a currency change there). Because the view model is Activity-scoped
 * (no nav back-stack), [load] cancels any prior fetch and resets identity + one-shot state before the
 * async read, so a re-pointed view model never shows — or lets a Save hit — the previously loaded account.
 *
 * "Has transactions" is asked of the ledger (does any leg sit on this account). An entry the app
 * cannot read could hide such a leg, so the ledger's health is read first: while it reports an
 * unreadable entry the form stays empty and the screen shows the cannot-be-read state, rather than
 * an editor that could unlock the currency of an account in use.
 *
 * A read that throws (a stored row that cannot be decoded) becomes [EditAccountUiState.loadFailed],
 * reported to [ErrorReporter], instead of crashing the app; [retry] reads the health and the same
 * account again.
 */
@HiltViewModel
class EditAccountViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val currencies: CurrencyRepository,
    private val ledger: LedgerQueries,
    private val observeLedgerHealth: ObserveLedgerHealth,
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
                feeMode = null,
                feeValueInput = "",
                currencyLocked = false,
                isSubmitting = false,
                error = null,
                errorField = null,
                saved = false,
                loadFailed = false,
                loadFailure = null,
            )
        }
        loadJob = viewModelScope.launch {
            try {
                readInto(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors.report("Edit account could not read account $id", e)
                _state.update { it.copy(loadFailed = true, loadFailure = e.toLoadFailure()) }
            }
        }
    }

    /** Reads the last requested account again after a failed read. Before any [load] there is none. */
    fun retry() {
        lastLoadedId?.let(::load)
    }

    private suspend fun readInto(id: Long) {
        // Fails with UnreadableRow while an entry the app cannot read is stored.
        observeLedgerHealth().first()
        val existing = accounts.findById(id)
        // A newer load() cancelled this one; stop here rather than overwrite the fresh state.
        currentCoroutineContext().ensureActive()
        if (existing == null) {
            _state.update { it.copy(error = UiMessage.AccountNotFound) }
            return
        }
        val locked = ledger.isAccountInUse(id)
        currentCoroutineContext().ensureActive()
        _state.update { it.populatedFrom(existing, locked) }
    }

    fun selectCurrency(code: String) = _state.update {
        it.copy(
            selectedCurrencyCode = code,
            feeValueInput = feeInputAfterCurrencyChange(it.feeMode, it.selectedCurrencyCode, code, it.feeValueInput),
            error = null, errorField = null,
        )
    }

    fun setName(name: String) = _state.update { it.copy(name = name, error = null, errorField = null) }

    fun selectAccountType(typeKey: String) = _state.update { it.copy(accountTypeKey = typeKey, error = null, errorField = null) }

    fun setOpeningBalance(text: String) = _state.update { it.copy(openingBalanceInput = text, error = null, errorField = null) }

    fun setCreditLimit(text: String) = _state.update { it.copy(creditLimitInput = text, error = null, errorField = null) }

    /** Clear the one-shot `saved` signal after the screen has navigated away, so a re-open won't bounce. */
    fun acknowledgeSaved() = _state.update { it.copy(saved = false, error = null, errorField = null) }

    fun selectFeeMode(mode: FeeMode?) = _state.update {
        it.copy(feeMode = mode, feeValueInput = feeInputAfterModeChange(it.feeMode, mode, it.feeValueInput),
            error = null, errorField = null)
    }

    fun setFeeValue(text: String) = _state.update { it.copy(feeValueInput = text, error = null, errorField = null) }

    fun submit() {
        val snapshot = _state.value
        val loaded = snapshot.loaded ?: return // UI guards this via canSubmit
        val currencyCode = snapshot.selectedCurrencyCode ?: return
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null, errorField = null) }
            try {
                // Rebuild the amounts in the selected currency so they match when the currency changes.
                val openingBalance = parseAccountField(AccountInputField.OPENING_BALANCE) {
                    moneyParser.parseOpeningBalance(snapshot.openingBalanceInput, currencyCode)
                }
                // The form holds the stored limit as text, so saving untouched keeps it; a type that is
                // not owed when negative saves none.
                val creditLimit = parseAccountField(AccountInputField.CREDIT_LIMIT) {
                    moneyParser.parseCreditLimit(snapshot.creditLimitInput, snapshot.showsCreditLimit, currencyCode)
                }
                val defaultFee = parseAccountField(AccountInputField.DEFAULT_FEE) {
                    parseDefaultFee(snapshot.feeMode, snapshot.feeValueInput, currencyCode, moneyParser)
                }
                updateAccount(
                    id = loaded.id,
                    name = snapshot.name.trim(),
                    typeKey = snapshot.accountTypeKey,
                    currencyCode = currencyCode,
                    openingBalance = openingBalance,
                    creditLimit = creditLimit,
                    defaultFee = defaultFee,
                )
                _state.update { it.copy(isSubmitting = false, saved = true) }
            } catch (e: AccountFieldFailure) {
                _state.update { it.copy(isSubmitting = false, error = e.uiMessage, errorField = e.field) }
            } catch (e: WalletException) {
                // Includes AccountCurrencyLocked / AccountNotFound — surface it to the user.
                _state.update { it.copy(isSubmitting = false, error = e.toUiMessage()) }
            } catch (e: IllegalArgumentException) {
                // Blank name (Account invariant), an amount or limit the parser refused, or a fee default
                // the form refused — surface it.
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
        creditLimitInput = account.creditLimit?.let { moneyParser.toInputString(it.amount) } ?: "",
        feeMode = account.defaultFee?.mode,
        feeValueInput = account.defaultFee?.let { moneyParser.toInputString(it.value) } ?: "",
        currencyLocked = locked,
        isSubmitting = false,
        error = null,
        errorField = null,
        saved = false,
    )
}
