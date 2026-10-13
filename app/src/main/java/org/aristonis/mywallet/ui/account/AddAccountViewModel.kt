package org.aristonis.mywallet.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.di.LocaleDefaults
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.AccountTypeRegistry
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.usecase.CreateAccount
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage
import javax.inject.Inject

/** Immutable snapshot the add-account screen renders from. */
data class AddAccountUiState(
    val currencies: List<Currency> = emptyList(),
    val selectedCurrencyCode: String? = null,
    val name: String = "",
    val accountTypeKey: String = AccountTypeRegistry.BuiltIns.CASH.key,
    val openingBalanceInput: String = "",
    val creditLimitInput: String = "",
    val feeMode: FeeMode? = null,
    val feeValueInput: String = "",
    val isSubmitting: Boolean = false,
    val error: UiMessage? = null,
    val created: Boolean = false,
) {
    val canSubmit: Boolean
        get() = selectedCurrencyCode != null && name.isNotBlank() && !isSubmitting

    /** The limit field is offered only for a type whose negative balance is money owed. */
    val showsCreditLimit: Boolean
        get() = isOwedWhenNegative(accountTypeKey)
}

/**
 * Add another account (name / type / currency / opening balance, plus a credit limit for a type that
 * is owed when negative) via [CreateAccount]. Unlike onboarding it does NOT touch the base currency.
 */
@HiltViewModel
class AddAccountViewModel @Inject constructor(
    private val currencies: CurrencyRepository,
    private val createAccount: CreateAccount,
    private val localeDefaults: LocaleDefaults,
    private val moneyParser: MoneyParser,
) : ViewModel() {

    private val _state = MutableStateFlow(AddAccountUiState())
    val state: StateFlow<AddAccountUiState> = _state.asStateFlow()

    init {
        currencies.observeAll()
            .onEach { list ->
                _state.update { current ->
                    current.copy(
                        currencies = list,
                        selectedCurrencyCode = current.selectedCurrencyCode ?: defaultCurrency(list),
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    private fun updateDraft(change: (AddAccountUiState) -> AddAccountUiState) = _state.update {
        if (it.isSubmitting) it else change(it)
    }

    fun selectCurrency(code: String) = updateDraft {
        it.copy(
            selectedCurrencyCode = code,
            feeValueInput = feeInputAfterCurrencyChange(it.feeMode, it.selectedCurrencyCode, code, it.feeValueInput),
            error = null,
        )
    }

    fun setName(name: String) = updateDraft { it.copy(name = name, error = null) }

    fun selectAccountType(typeKey: String) = updateDraft { it.copy(accountTypeKey = typeKey, error = null) }

    fun setOpeningBalance(text: String) = updateDraft {
        it.copy(openingBalanceInput = text, error = null)
    }

    fun setCreditLimit(text: String) = updateDraft { it.copy(creditLimitInput = text, error = null) }

    fun selectFeeMode(mode: FeeMode?) = updateDraft {
        it.copy(feeMode = mode, feeValueInput = feeInputAfterModeChange(it.feeMode, mode, it.feeValueInput), error = null)
    }

    fun setFeeValue(text: String) = updateDraft { it.copy(feeValueInput = text, error = null) }

    fun submit() {
        val snapshot = _state.value
        if (snapshot.isSubmitting || snapshot.created) return
        val currencyCode = snapshot.selectedCurrencyCode ?: return
        if (!_state.compareAndSet(snapshot, snapshot.copy(isSubmitting = true, error = null))) return
        viewModelScope.launch {
            try {
                val openingBalance = moneyParser.parseOpeningBalance(snapshot.openingBalanceInput, currencyCode)
                val creditLimit = moneyParser.parseCreditLimit(
                    snapshot.creditLimitInput, snapshot.showsCreditLimit, currencyCode,
                )
                createAccount(
                    name = snapshot.name.trim(),
                    typeKey = snapshot.accountTypeKey,
                    currencyCode = currencyCode,
                    openingBalance = openingBalance,
                    creditLimit = creditLimit,
                    defaultFee = parseDefaultFee(snapshot.feeMode, snapshot.feeValueInput, currencyCode, moneyParser),
                )
                _state.update { it.copy(isSubmitting = false, created = true) }
            } catch (e: WalletException) {
                _state.update { it.copy(isSubmitting = false, error = e.toUiMessage()) }
            } catch (e: IllegalArgumentException) {
                // Typed parse and missing-choice failures carry their own message; anything else is a
                // broken invariant whose text was written for a stack trace, not for a person.
                _state.update { it.copy(isSubmitting = false, error = e.toUiMessage()) }
            }
        }
    }

    /**
     * Clear the one-shot `created` signal and reset the form once the screen has navigated away. The
     * view model is Activity-scoped (no nav back-stack), so a stale `created == true` would otherwise
     * bounce a re-opened screen straight back to Home before the user could add a second account.
     */
    fun acknowledgeCreated() = _state.update {
        it.copy(
            created = false, name = "", openingBalanceInput = "", creditLimitInput = "",
            feeMode = null, feeValueInput = "", error = null,
        )
    }

    private fun defaultCurrency(currencies: List<Currency>): String? =
        currencies.firstOrNull { it.code == localeDefaults.currencyCode }?.code ?: currencies.firstOrNull()?.code
}
