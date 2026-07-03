package org.aristonis.mywallet.ui.onboarding

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
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.AccountTypeRegistry
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ThemePreference
import org.aristonis.mywallet.di.LocaleDefaults
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.usecase.CreateAccount
import org.aristonis.mywallet.domain.usecase.SetBaseCurrency
import javax.inject.Inject

/** Immutable snapshot the onboarding screen renders from (unidirectional data flow). */
data class OnboardingUiState(
    val currencies: List<Currency> = emptyList(),
    val selectedCurrencyCode: String? = null,
    val accountName: String = "",
    val accountTypeKey: String = AccountTypeRegistry.BuiltIns.CASH.key,
    val openingBalanceInput: String = "",
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val completed: Boolean = false,
) {
    /** The UI enables the finish button only when both required choices are present. */
    val canSubmit: Boolean
        get() = selectedCurrencyCode != null && accountName.isNotBlank() && !isSubmitting
}

/**
 * First-run onboarding: pick the base currency + create the first account. Write-only — it does not
 * read Settings (routing to onboarding-vs-home is a later concern; see backlog "Onboarding gate").
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    currencies: CurrencyRepository,
    private val setBaseCurrency: SetBaseCurrency,
    private val createAccount: CreateAccount,
    private val localeDefaults: LocaleDefaults,
    private val moneyParser: MoneyParser,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        // Seeded currencies stream in from Room; pre-select the first so the form is valid by default.
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

    fun setAccountName(name: String) = _state.update { it.copy(accountName = name) }

    fun selectAccountType(typeKey: String) = _state.update { it.copy(accountTypeKey = typeKey) }

    fun setOpeningBalance(text: String) = _state.update { it.copy(openingBalanceInput = text) }

    fun selectTheme(theme: ThemePreference) = _state.update { it.copy(theme = theme) }

    fun submit() {
        val snapshot = _state.value
        val currencyCode = snapshot.selectedCurrencyCode ?: return // UI guards this via canSubmit
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            try {
                val openingBalance = moneyParser.parseOpeningBalance(snapshot.openingBalanceInput, currencyCode)
                // Create the account FIRST: it carries all the failure-prone validation (blank name, bad
                // amount), whereas SetBaseCurrency only fails on an unknown code — impossible from the
                // picker. So a validation failure writes nothing (no half-onboarded state). Order is
                // otherwise free: CreateAccount doesn't read settings. Full atomicity is a backlog item.
                createAccount(
                    name = snapshot.accountName.trim(),
                    typeKey = snapshot.accountTypeKey,
                    currencyCode = currencyCode,
                    openingBalance = openingBalance,
                )
                setBaseCurrency(currencyCode, snapshot.theme)
                _state.update { it.copy(isSubmitting = false, completed = true) }
            } catch (e: WalletException) {
                // Typed domain failure (e.g. unknown currency) — surface, never swallow (fail-loud).
                _state.update { it.copy(isSubmitting = false, error = e.message) }
            } catch (e: IllegalArgumentException) {
                // Invariant violations: blank name (Account) or unparseable amount (NumberFormatException).
                _state.update { it.copy(isSubmitting = false, error = e.message) }
            }
        }
    }

    /** Prefer the device-locale currency when it's one of the seeded options, else the first. */
    private fun defaultCurrency(currencies: List<Currency>): String? =
        currencies.firstOrNull { it.code == localeDefaults.currencyCode }?.code ?: currencies.firstOrNull()?.code
}
