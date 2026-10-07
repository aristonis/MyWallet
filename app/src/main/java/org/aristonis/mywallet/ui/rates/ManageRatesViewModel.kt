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
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage
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
    val error: UiMessage? = null,
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
    private val moneyParser: MoneyParser,
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
                val rate = moneyParser.parseRate(row.input)
                setExchangeRate(currencyCode, rate)
                updateRow(currencyCode) { it.copy(error = null) } // currentRate refreshes from the stream
            } catch (e: WalletException.CurrencyNotFound) {
                updateRow(currencyCode) { it.copy(error = e.toUiMessage()) }
            } catch (e: IllegalArgumentException) {
                // Blank / unparseable / out-of-range / non-positive — parseRate names which one.
                updateRow(currencyCode) { it.copy(error = e.toUiMessage()) }
            }
        }
    }

    private fun updateRow(currencyCode: String, transform: (RateRow) -> RateRow) = _state.update { s ->
        s.copy(rows = s.rows.map { if (it.currencyCode == currencyCode) transform(it) else it })
    }

    /**
     * The base the rows currently on screen were typed against. A rate means "so many units of THE
     * BASE", so the moment the base changes every typed number means something else.
     */
    private var rowsBase: String? = null

    private fun buildRows(snapshot: Snapshot, previous: List<RateRow>): List<RateRow> {
        // Discard anything typed when the base has moved. The view model outlives navigation, so the
        // field would otherwise still hold a number expressed against the OLD base, and one Save
        // would store it as a new-base rate — a permanent error on every figure in that currency,
        // with nothing to indicate it. Keyed on the base rather than on any change at all, so an
        // unrelated edit elsewhere does not throw away what the user is in the middle of typing.
        val carried = if (rowsBase == snapshot.base) previous else emptyList()
        rowsBase = snapshot.base
        // Only currencies that active accounts use need a rate — this mirrors ComputeNetWorth, which
        // excludes archived accounts, so we never prompt for a rate that can't change the net worth.
        val usedNonBase = snapshot.accounts
            .filterNot { it.archived }
            .map { it.currencyCode }
            .toSortedSet()
            .filter { it != snapshot.base }

        return usedNonBase.map { code ->
            val currentRate = snapshot.rates.firstOrNull { it.currencyCode == code }?.rateToBase
            val prior = carried.firstOrNull { it.currencyCode == code }
            RateRow(
                currencyCode = code,
                symbol = snapshot.currencies.firstOrNull { it.code == code }?.symbol ?: code,
                currentRate = currentRate,
                // Sticky: once a row exists, keep the user's text; only seed it from a saved rate on first load.
                input = prior?.input ?: currentRate?.let { moneyParser.toInputString(it) } ?: "",
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
