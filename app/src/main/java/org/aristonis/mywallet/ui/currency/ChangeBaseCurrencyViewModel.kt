package org.aristonis.mywallet.ui.currency

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.usecase.ChangeBaseCurrency
import javax.inject.Inject

/** Immutable snapshot the change-base-currency screen renders from. */
data class ChangeBaseCurrencyUiState(
    val currentBase: String = "",
    val selectable: List<Currency> = emptyList(),
    val selectedCode: String? = null,
    /** True when the chosen currency has no stored rate, so the form has to ask for one. */
    val needsRate: Boolean = false,
    val rateInput: String = "",
    val isWorking: Boolean = false,
    val error: String? = null,
    val applied: Boolean = false,
) {
    val canApply: Boolean
        get() = selectedCode != null && !isWorking && (!needsRate || rateInput.isNotBlank())
}

/**
 * Changes the app's base currency.
 *
 * The rate for the new base is asked for here rather than on Manage Rates, which only lists
 * currencies an account actually holds — so a currency you do not own yet could never be given a
 * rate there, and moving country, holding only old-country accounts, is the usual reason to do this.
 *
 * Nothing recorded is rewritten: balances and transaction amounts stay in their own currencies and
 * only the conversion layer moves. It cannot be undone, though, because the rates it re-expresses
 * replace the ones the user originally typed — which is why the screen confirms with the figures
 * before and after rather than treating this as an ordinary setting.
 */
@HiltViewModel
class ChangeBaseCurrencyViewModel @Inject constructor(
    currencies: CurrencyRepository,
    rates: RateRepository,
    settings: SettingsRepository,
    private val changeBaseCurrency: ChangeBaseCurrency,
    private val moneyParser: MoneyParser,
) : ViewModel() {

    private val _state = MutableStateFlow(ChangeBaseCurrencyUiState())
    val state: StateFlow<ChangeBaseCurrencyUiState> = _state.asStateFlow()

    private var ratedCodes: Set<String> = emptySet()

    init {
        combine(
            currencies.observeAll(),
            rates.observeAll(),
            settings.observe(),
        ) { currencyList, rateList, currentSettings ->
            ratedCodes = rateList.map { it.currencyCode }.toSet()
            currencyList.filterNot { it.code == currentSettings.baseCurrencyCode } to
                currentSettings.baseCurrencyCode
        }
            .onEach { (selectable, base) ->
                _state.update { it.copy(selectable = selectable, currentBase = base) }
            }
            .launchIn(viewModelScope)
    }

    fun select(code: String) = _state.update {
        // Clear any typed rate: it was entered for a different currency, and carrying it over would
        // apply a number that was never meant for the one now chosen.
        it.copy(
            selectedCode = code,
            needsRate = code !in ratedCodes,
            rateInput = "",
            error = null,
            applied = false,
        )
    }

    fun setRateInput(text: String) = _state.update { it.copy(rateInput = text, error = null) }

    /** Clears the one-shot outcome so returning to the screen does not replay a finished change. */
    fun acknowledge() = _state.update { it.copy(applied = false, error = null) }

    fun apply() {
        val snapshot = _state.value
        val code = snapshot.selectedCode ?: return
        viewModelScope.launch {
            _state.update { it.copy(isWorking = true, error = null) }
            try {
                val rate = if (snapshot.needsRate) moneyParser.parseRate(snapshot.rateInput) else null
                changeBaseCurrency(code, rate)
                _state.update { it.copy(isWorking = false, applied = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WalletException) {
                _state.update { it.copy(isWorking = false, error = e.message) }
            } catch (e: IllegalArgumentException) {
                _state.update { it.copy(isWorking = false, error = e.message) }
            }
        }
    }
}
