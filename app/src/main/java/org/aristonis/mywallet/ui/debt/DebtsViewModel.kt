package org.aristonis.mywallet.ui.debt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.di.DefaultDispatcher
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.DebtState
import org.aristonis.mywallet.domain.model.DebtSummary
import org.aristonis.mywallet.domain.model.DebtTotals
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.usecase.ObserveDebts
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
import org.aristonis.mywallet.ui.format.display
import org.aristonis.mywallet.ui.load.ReadAttempts
import org.aristonis.mywallet.ui.load.toLoadFailure
import org.aristonis.mywallet.ui.message.UiMessage
import javax.inject.Inject

/** Native currency stays on each row even when a conversion rate is missing. */
data class DebtRow(val summary: DebtSummary, val remainingDisplay: String)

data class DebtsUiState(
    val selectedSide: DebtSide = DebtSide.I_OWE,
    val open: List<DebtRow> = emptyList(),
    val settled: List<DebtRow> = emptyList(),
    val totals: DebtTotals? = null,
    val iOweDisplay: String? = null,
    val owedToMeDisplay: String? = null,
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val loadError: UiMessage? = null,
)

@HiltViewModel
class DebtsViewModel @Inject constructor(
    observeDebts: ObserveDebts,
    observeLedgerHealth: ObserveLedgerHealth,
    currencies: CurrencyRepository,
    moneyFormatter: MoneyFormatter,
    private val today: TodaySource,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
    private val errors: ErrorReporter,
) : ViewModel() {
    private val attempts = ReadAttempts()
    private val selected = MutableStateFlow(DebtSide.I_OWE)

    val state: StateFlow<DebtsUiState> = attempts.reading(loading = { DebtsUiState(selectedSide = selected.value) }) {
        combine(observeDebts(), observeLedgerHealth(), currencies.observeAll(), selected) { snapshot, _, codes, side ->
            val rows = snapshot.debts.filter { it.debt.side == side }.map {
                DebtRow(it, moneyFormatter.display(it.remaining, codes))
            }
            val totals = snapshot.totals as? DebtTotals.Amounts
            DebtsUiState(
                selectedSide = side,
                open = rows.filter { it.summary.state != DebtState.SETTLED },
                settled = rows.filter { it.summary.state == DebtState.SETTLED },
                totals = snapshot.totals,
                iOweDisplay = totals?.let { moneyFormatter.display(it.iOwe, codes) },
                owedToMeDisplay = totals?.let { moneyFormatter.display(it.owedToMe, codes) },
                isLoading = false,
            )
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            errors.report("Debts could not read the wallet", failure)
            emit(DebtsUiState(selectedSide = selected.value, isLoading = false, loadFailed = true,
                loadError = failure.toLoadFailure()))
        }
    }.flowOn(defaultDispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DebtsUiState())

    fun selectSide(side: DebtSide) { selected.value = side }
    fun retry() = attempts.retry()
    fun refreshToday() = today.refresh()

    private companion object { const val STOP_TIMEOUT_MS = 5_000L }
}
