package org.aristonis.mywallet.ui.rates

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
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.usecase.SetExchangeRate
import java.math.BigDecimal
import javax.inject.Inject

/** One editable rate line: a non-base currency, its saved rate (if any), and the field the user types in. */
data class RateRow(
    val currencyCode: String,
    val symbol: String,
    val currentRate: BigDecimal?,
    val input: String,
    val error: String? = null,
)

/** Immutable snapshot the manage-rates screen renders from. */
data class ManageRatesUiState(
    val baseCurrencyCode: String? = null,
    val rows: List<RateRow> = emptyList(),
    val isLoading: Boolean = true,
)

/**
 * Manage the exchange rates for the currencies the user's accounts actually use. The base currency
 * is implicitly 1 and is excluded. Each row is saved independently via [SetExchangeRate]; a re-emit
 * of the rate stream refreshes the saved value without wiping whatever the user is mid-typing.
 */
@HiltViewModel
class ManageRatesViewModel @Inject constructor(
    accounts: AccountRepository,
    currencies: CurrencyRepository,
    rates: RateRepository,
    settings: SettingsRepository,
    private val setExchangeRate: SetExchangeRate,
) : ViewModel() {

    private val _state = MutableStateFlow(ManageRatesUiState())
    val state: StateFlow<ManageRatesUiState> = _state.asStateFlow()

    init {
        combine(
            accounts.observeAll(),
            currencies.observeAll(),
            rates.observeAll(),
            settings.observe(),
        ) { accountList, currencyList, rateList, currentSettings ->
            Snapshot(currentSettings.baseCurrencyCode, accountList, currencyList, rateList)
        }
            .onEach { snapshot ->
                _state.update { current ->
                    current.copy(
                        baseCurrencyCode = snapshot.base,
                        rows = buildRows(snapshot, current.rows),
                        isLoading = false,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    fun setRateInput(currencyCode: String, text: String) = updateRow(currencyCode) {
        it.copy(input = text, error = null)
    }

    fun submit(currencyCode: String) {
        val row = _state.value.rows.firstOrNull { it.currencyCode == currencyCode } ?: return
        viewModelScope.launch {
            try {
                val rate = parseRate(row.input)
                setExchangeRate(currencyCode, rate)
                updateRow(currencyCode) { it.copy(error = null) } // currentRate refreshes from the stream
            } catch (e: WalletException.CurrencyNotFound) {
                updateRow(currencyCode) { it.copy(error = e.message) }
            } catch (e: IllegalArgumentException) {
                // Blank / unparseable / out-of-range / non-positive — parseRate framed a user-facing message.
                updateRow(currencyCode) { it.copy(error = e.message) }
            }
        }
    }

    private fun updateRow(currencyCode: String, transform: (RateRow) -> RateRow) = _state.update { s ->
        s.copy(rows = s.rows.map { if (it.currencyCode == currencyCode) transform(it) else it })
    }

    /**
     * Parse the typed rate, failing loud with a user-facing message. Rejects blank, non-numeric,
     * non-positive, and an absurd exponent (which would otherwise blow up toPlainString() to an OOM).
     */
    private fun parseRate(input: String): BigDecimal {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Enter a rate" }
        val parsed = try {
            BigDecimal(trimmed)
        } catch (_: NumberFormatException) {
            throw IllegalArgumentException("Enter a valid number")
        }
        require(parsed.scale() in -MAX_RATE_SCALE..MAX_RATE_SCALE) { "Enter a realistic rate" }
        require(parsed.signum() > 0) { "Rate must be greater than 0" }
        return parsed
    }

    private companion object {
        // Any real rate sits far inside this scale; an extreme exponent is a typo that would also
        // OOM toPlainString(), so we reject it at the boundary rather than crash later.
        private const val MAX_RATE_SCALE = 30
    }

    private fun buildRows(snapshot: Snapshot, previous: List<RateRow>): List<RateRow> {
        // Only currencies that active accounts use need a rate — this mirrors ComputeNetWorth, which
        // excludes archived accounts, so we never prompt for a rate that can't change the net worth.
        val usedNonBase = snapshot.accounts
            .filterNot { it.archived }
            .map { it.currencyCode }
            .toSortedSet()
            .filter { it != snapshot.base }

        return usedNonBase.map { code ->
            val currentRate = snapshot.rates.firstOrNull { it.currencyCode == code }?.rateToBase
            val prior = previous.firstOrNull { it.currencyCode == code }
            RateRow(
                currencyCode = code,
                symbol = snapshot.currencies.firstOrNull { it.code == code }?.symbol ?: code,
                currentRate = currentRate,
                // Sticky: once a row exists, keep the user's text; only seed it from a saved rate on first load.
                input = prior?.input ?: currentRate?.toPlainString() ?: "",
                error = prior?.error,
            )
        }
    }

    private data class Snapshot(
        val base: String,
        val accounts: List<Account>,
        val currencies: List<Currency>,
        val rates: List<ExchangeRate>,
    )
}
