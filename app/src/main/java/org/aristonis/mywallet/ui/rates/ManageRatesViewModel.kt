package org.aristonis.mywallet.ui.rates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.di.DefaultDispatcher
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.service.DebtRemaining
import org.aristonis.mywallet.domain.usecase.GetAccountBalances
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
import org.aristonis.mywallet.domain.usecase.SetExchangeRate
import org.aristonis.mywallet.ui.load.ReadAttempts
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage

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
    val loadFailed: Boolean = false,
)

/**
 * Manage rates needed by active accounts, owing cards and positive debts. The base currency is
 * implicitly 1 and is excluded. Each row is saved independently via [SetExchangeRate]; a re-emit
 * of the rate stream refreshes the saved value without wiping whatever the user is mid-typing.
 */
@HiltViewModel
class ManageRatesViewModel @Inject constructor(
    getAccountBalances: GetAccountBalances,
    ledger: LedgerQueries,
    currencies: CurrencyRepository,
    rates: RateRepository,
    settings: SettingsRepository,
    private val setExchangeRate: SetExchangeRate,
    private val moneyParser: MoneyParser,
    observeLedgerHealth: ObserveLedgerHealth,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    private val errors: ErrorReporter,
) : ViewModel() {

    private val _state = MutableStateFlow(ManageRatesUiState())
    val state: StateFlow<ManageRatesUiState> = _state.asStateFlow()

    private val attempts = ReadAttempts()
    private var carriedRows: List<RateRow> = emptyList()

    init {
        attempts.reading<ReadResult>(loading = { ReadResult.Loading }) {
            combine<Snapshot, Unit, ReadResult>(snapshotFlow(getAccountBalances, ledger, currencies, rates, settings), observeLedgerHealth()) {
                snapshot, _ -> ReadResult.Ready(snapshot)
            }.flowOn(defaultDispatcher).catch { error ->
                if (error is CancellationException) throw error
                errors.report("Rates could not read the wallet", IllegalStateException(error.javaClass.simpleName))
                emit(ReadResult.Failed)
            }
        }.onEach { result ->
            when (result) {
                ReadResult.Loading -> _state.update { it.copy(isLoading = true, loadFailed = false) }
                ReadResult.Failed -> _state.update { it.copy(rows = emptyList(), isLoading = false, loadFailed = true) }
                is ReadResult.Ready -> {
                    carriedRows = buildRows(result.snapshot, carriedRows)
                    _state.value = ManageRatesUiState(result.snapshot.base, carriedRows, isLoading = false)
                }
            }
        }.launchIn(viewModelScope)
    }

    private fun snapshotFlow(
        balances: GetAccountBalances,
        ledger: LedgerQueries,
        currencies: CurrencyRepository,
        rates: RateRepository,
        settings: SettingsRepository,
    ) = combine(balances(), ledger.observeDebtLegs(), currencies.observeAll(), rates.observeAll(), settings.observe()) {
        accounts, debtLegs, currencyList, rateList, currentSettings ->
        val used = accounts.filter { !it.account.archived || (it.credit != null && it.balance.amount.signum() < 0) }
            .map { it.balance.currencyCode } + DebtRemaining.byDebt(debtLegs).values
            .filter { it.remaining.amount.signum() > 0 }.map { it.remaining.currencyCode }
        Snapshot(currentSettings.baseCurrencyCode, used.toSet(), currencyList, rateList)
    }

    fun retry() = attempts.retry()

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

    private fun updateRow(currencyCode: String, transform: (RateRow) -> RateRow) {
        carriedRows = carriedRows.map { if (it.currencyCode == currencyCode) transform(it) else it }
        _state.update { s -> s.copy(rows = s.rows.map { if (it.currencyCode == currencyCode) transform(it) else it }) }
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
        val usedNonBase = snapshot.currencyCodes.sorted().filter { it != snapshot.base }

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

    private sealed interface ReadResult {
        data object Loading : ReadResult
        data object Failed : ReadResult
        data class Ready(val snapshot: Snapshot) : ReadResult
    }

    private data class Snapshot(
        val base: String,
        val currencyCodes: Set<String>,
        val currencies: List<Currency>,
        val rates: List<ExchangeRate>,
    )
}
