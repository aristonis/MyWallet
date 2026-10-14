package org.aristonis.mywallet.ui.reports.charts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.di.DefaultDispatcher
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.NetWorthSeries
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.usecase.ComputeNetWorthSeries
import org.aristonis.mywallet.domain.usecase.GetAccountBalances
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
import org.aristonis.mywallet.ui.format.display
import org.aristonis.mywallet.ui.load.ReadAttempts
import org.aristonis.mywallet.ui.load.loadFailure
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.aristonis.mywallet.ui.window.TrackingWindowHolder
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Net worth: the restated series ([ComputeNetWorthSeries]) for one window, formatted for the line and
 * the exact table. The series is the domain's; this only formats it and picks the lowest and highest
 * point for the chart's description.
 *
 * The series is rebuilt when the window changes and when the day does ([onScreenStart]), since its
 * buckets and its last point depend on today. Balances are summed from leg projections, which skip an
 * entry of a kind the app cannot read, so the ledger's health is read beside it as on Home.
 *
 * A failed read becomes the failed state for that window and is reported; [retry] reads again. All of
 * it runs on [defaultDispatcher].
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class NetWorthViewModel @Inject constructor(
    private val getAccountBalances: GetAccountBalances,
    private val computeNetWorthSeries: ComputeNetWorthSeries,
    observeLedgerHealth: ObserveLedgerHealth,
    currencies: CurrencyRepository,
    private val moneyFormatter: MoneyFormatter,
    private val today: TodayProvider,
    savedStateHandle: SavedStateHandle,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
    private val errors: ErrorReporter,
) : ViewModel() {

    private val holder = TrackingWindowHolder(
        savedState = savedStateHandle.apply { seedChartWindow(WINDOW_KEY) },
        key = WINDOW_KEY,
        today = today,
        defaultWindow = { TrackingWindow.Period(TrackingPeriod.MONTH, it) },
    )

    /** The day the series is built for; moved on by [onScreenStart]. */
    private val day = MutableStateFlow(today.today())

    private val attempts = ReadAttempts()

    /** The date bar's callbacks, wired to this screen's window. */
    val windowActions: DateWindowActions get() = holder.actions

    private val windowResults: Flow<WindowResult> =
        combine(holder.window, day, ::Pair).flatMapLatest { (window, onDay) ->
            computeNetWorthSeries(getAccountBalances(), window.range, onDay)
                // The series combines four reads that each answer a write on their own, so it can
                // emit a point built from new balances and old dated legs. Waiting for the reads to
                // settle drops that point; conflating alone would not, as an idle collector takes it.
                .debounce(SERIES_SETTLE_MS)
                .distinctUntilChanged()
                .map<NetWorthSeries, WindowResult> { WindowResult(window, it) }
                .catch { error ->
                    if (error is CancellationException) throw error
                    errors.report("Net worth for ${window.range} could not be read", error)
                    emit(WindowResult(window, series = null))
                }
        }

    /** Null once the currencies cannot be read; the screen then shows the failure. */
    private val currencyList: Flow<List<Currency>?> = currencies.observeAll()
        .catch<List<Currency>?> { error ->
            if (error is CancellationException) throw error
            errors.report("Net worth could not read the currencies", error)
            emit(null)
        }

    private val healthFailure: Flow<UiMessage?> = observeLedgerHealth.loadFailure(errors, screen = "Net worth")

    val state: StateFlow<NetWorthUiState> =
        attempts.reading(loading = { NetWorthUiState(window = holder.window.value) }) {
            combine(windowResults, currencyList, healthFailure) { result, currencies, failure ->
                if (failure == null) stateFor(result, currencies) else NetWorthUiState(result.window, loadFailed = true)
            }
        }
            // The bar shows the tapped window at once; the previous figures stay, marked as updating,
            // until the new window's series settles.
            .combine(holder.window) { shown, current ->
                if (shown.window == current) shown else shown.copy(window = current, isUpdating = true)
            }
            .flowOn(defaultDispatcher)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NetWorthUiState(holder.window.value))

    /** Reads the series again after a failure, showing loading until it answers. */
    fun retry() = attempts.retry()

    /**
     * The screen is back in view. A window that follows today moves with it, and the series is
     * rebuilt for today, so a screen left open past midnight gains the new day's point.
     */
    fun onScreenStart() {
        holder.refreshToday()
        day.value = today.today()
    }

    private fun stateFor(result: WindowResult, currencies: List<Currency>?): NetWorthUiState {
        val series = result.series
        if (series == null || currencies == null) return NetWorthUiState(result.window, loadFailed = true)
        return NetWorthUiState(result.window, data = dataOf(series, currencies))
    }

    private fun dataOf(series: NetWorthSeries, currencies: List<Currency>): NetWorthData = when (series) {
        is NetWorthSeries.Trend -> {
            val balances = series.points.map { it.balance }
            val change = balances.last().compareTo(balances.first())
            NetWorthData.Trend(
                currencyCode = series.points.first().balance.currencyCode,
                points = series.points.map { NetWorthPointRow(it.date, it.balance.amount, moneyFormatter.display(it.balance, currencies)) },
                lowestDisplay = moneyFormatter.display(balances.min(), currencies),
                highestDisplay = moneyFormatter.display(balances.max(), currencies),
                direction = when {
                    change > 0 -> TrendDirection.UP
                    change < 0 -> TrendDirection.DOWN
                    else -> TrendDirection.FLAT
                },
                crossesZero = balances.min().amount.signum() < 0 && balances.max().amount.signum() > 0,
            )
        }
        is NetWorthSeries.CurrentOnly -> NetWorthData.CurrentOnly(
            currencyCode = series.balance.currencyCode,
            balanceDisplay = moneyFormatter.display(series.balance, currencies),
            reason = series.reason,
        )
        is NetWorthSeries.MissingRate -> NetWorthData.MissingRate(series.currencyCode)
        NetWorthSeries.TooManyLegs -> NetWorthData.TooManyLegs
    }

    /** One window's series; null when its read failed. */
    private data class WindowResult(val window: TrackingWindow, val series: NetWorthSeries?)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val WINDOW_KEY = "netWorthWindow"

        /**
         * How long the series must stay unchanged before it is shown. Long enough for the reads of
         * one write to answer, short enough not to be seen.
         */
        const val SERIES_SETTLE_MS = 100L
    }
}
