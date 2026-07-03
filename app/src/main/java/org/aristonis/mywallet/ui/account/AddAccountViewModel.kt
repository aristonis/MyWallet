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
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.usecase.CreateAccount
import javax.inject.Inject

/** Immutable snapshot the add-account screen renders from. */
data class AddAccountUiState(
    val currencies: List<Currency> = emptyList(),
    val selectedCurrencyCode: String? = null,
    val name: String = "",
    val accountTypeKey: String = AccountTypeRegistry.BuiltIns.CASH.key,
    val openingBalanceInput: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val created: Boolean = false,
) {
    val canSubmit: Boolean
        get() = selectedCurrencyCode != null && name.isNotBlank() && !isSubmitting
}

/**
 * Add another account (name / type / currency / opening balance) via [CreateAccount]. Unlike
 * onboarding it does NOT touch the base currency. SKELETON — behavior filled in after the RED test.
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

    fun selectCurrency(code: String) = _state.update { it.copy(selectedCurrencyCode = code) }

    fun setName(name: String) = _state.update { it.copy(name = name) }

    fun selectAccountType(typeKey: String) = _state.update { it.copy(accountTypeKey = typeKey) }

    fun setOpeningBalance(text: String) = _state.update { it.copy(openingBalanceInput = text) }

    fun submit() {
        val snapshot = _state.value
        val currencyCode = snapshot.selectedCurrencyCode ?: return // UI guards this via canSubmit
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            try {
                val openingBalance = moneyParser.parseOpeningBalance(snapshot.openingBalanceInput, currencyCode)
                createAccount(
                    name = snapshot.name.trim(),
                    typeKey = snapshot.accountTypeKey,
                    currencyCode = currencyCode,
                    openingBalance = openingBalance,
                )
                _state.update { it.copy(isSubmitting = false, created = true) }
            } catch (e: WalletException) {
                _state.update { it.copy(isSubmitting = false, error = e.message) }
            } catch (e: IllegalArgumentException) {
                // Blank name (Account invariant) or unparseable amount (NumberFormatException) — surface it.
                _state.update { it.copy(isSubmitting = false, error = e.message) }
            }
        }
    }

    /**
     * Clear the one-shot `created` signal and reset the form once the screen has navigated away. The
     * view model is Activity-scoped (no nav back-stack), so a stale `created == true` would otherwise
     * bounce a re-opened screen straight back to Home before the user could add a second account.
     */
    fun acknowledgeCreated() = _state.update {
        it.copy(created = false, name = "", openingBalanceInput = "", error = null)
    }

    private fun defaultCurrency(currencies: List<Currency>): String? =
        currencies.firstOrNull { it.code == localeDefaults.currencyCode }?.code ?: currencies.firstOrNull()?.code
}
