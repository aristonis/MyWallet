package org.aristonis.mywallet.ui.reports.charts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.di.DefaultDispatcher
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryTotal
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.usecase.CategoryBreakdownResult
import org.aristonis.mywallet.domain.usecase.ComputeCategoryBreakdown
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
import org.aristonis.mywallet.ui.CategoryLabel
import org.aristonis.mywallet.ui.format.display
import org.aristonis.mywallet.ui.label
import org.aristonis.mywallet.ui.load.ReadAttempts
import org.aristonis.mywallet.ui.load.loadFailure
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.aristonis.mywallet.ui.window.TrackingWindowHolder
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Spending breakdown: the expense side of the category breakdown Tracking reads, for one window.
 *
 * The amounts are the domain's ([ComputeCategoryBreakdown]): each leg converted at its stored rate,
 * refunds subtracted from their category. Nothing is summed or converted here; the view model only
 * formats the totals and keeps their exact values for the chart's scale.
 *
 * The window starts on the one Tracking passed in the route ([seedChartWindow]) and then moves with
 * this screen's date bar. A failed read, of the window or of the ledger's health, becomes the failed
 * state for that window and is reported; [retry] reads again. All of it runs on [defaultDispatcher].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SpendingBreakdownViewModel @Inject constructor(
    private val computeCategoryBreakdown: ComputeCategoryBreakdown,
    observeLedgerHealth: ObserveLedgerHealth,
    categories: CategoryRepository,
    currencies: CurrencyRepository,
    private val moneyFormatter: MoneyFormatter,
    today: TodayProvider,
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

    private val attempts = ReadAttempts()

    /** The date bar's callbacks, wired to this screen's window. */
    val windowActions: DateWindowActions get() = holder.actions

    private val windowResults: Flow<WindowResult> = holder.window.flatMapLatest { window ->
        computeCategoryBreakdown(window.range)
            .map<CategoryBreakdownResult, WindowResult> { WindowResult(window, it) }
            .catch { error ->
                if (error is CancellationException) throw error
                errors.report("Spending breakdown for ${window.range} could not be read", error)
                emit(WindowResult(window, breakdown = null))
            }
    }

    /** Null once the categories or currencies cannot be read; the screen then shows the failure. */
    private val lookups: Flow<Lookups?> =
        combine(categories.observeAll(), currencies.observeAll(), ::Lookups)
            .catch<Lookups?> { error ->
                if (error is CancellationException) throw error
                errors.report("Spending breakdown could not read the categories or currencies", error)
                emit(null)
            }

    private val healthFailure: Flow<UiMessage?> = observeLedgerHealth.loadFailure(errors, screen = "Spending breakdown")

    val state: StateFlow<SpendingUiState> =
        attempts.reading(loading = { SpendingUiState(window = holder.window.value) }) {
            combine(windowResults, lookups, healthFailure) { result, lookup, failure ->
                if (failure == null) stateFor(result, lookup) else SpendingUiState(result.window, loadFailed = true)
            }
        }
            // The bar shows the tapped window at once; the previous figures stay, marked as updating,
            // until the new window's breakdown answers.
            .combine(holder.window) { shown, current ->
                if (shown.window == current) shown else shown.copy(window = current, isUpdating = true)
            }
            .flowOn(defaultDispatcher)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SpendingUiState(holder.window.value))

    /** Reads the window and the lookups again after a failure, showing loading until they answer. */
    fun retry() = attempts.retry()

    /** Keeps a window that follows today on today's period when the screen comes back into view. */
    fun onScreenStart() = holder.refreshToday()

    private fun stateFor(result: WindowResult, lookup: Lookups?): SpendingUiState {
        val breakdown = result.breakdown
        if (breakdown == null || lookup == null) return SpendingUiState(result.window, loadFailed = true)
        return SpendingUiState(result.window, data = dataOf(breakdown, lookup))
    }

    private fun dataOf(result: CategoryBreakdownResult, lookup: Lookups): SpendingData {
        val breakdown = when (result) {
            is CategoryBreakdownResult.MissingRate -> return SpendingData.MissingRate(result.currencyCode)
            is CategoryBreakdownResult.Resolved -> result.breakdown
        }
        val byId = lookup.categories.associateBy { it.id }
        return SpendingData.Ready(
            currencyCode = breakdown.baseCurrencyCode,
            totalDisplay = moneyFormatter.display(breakdown.summary.expense, lookup.currencies),
            rows = breakdown.expense.map { rowOf(it, byId, lookup.currencies) },
        )
    }

    private fun rowOf(total: CategoryTotal, byId: Map<Long, Category>, currencyList: List<Currency>) = SpendingRow(
        id = total.categoryId,
        // An unknown id keeps its row: the money is real even when the category was deleted.
        label = byId[total.categoryId]?.label() ?: CategoryLabel.Unknown,
        amount = total.total.amount,
        amountDisplay = moneyFormatter.display(total.total, currencyList),
        previewParts = total.subCategories
            .mapNotNull { sub -> sub.subCategoryId?.let { id -> byId[id]?.label() ?: CategoryLabel.Unknown } }
            .take(PREVIEW_COUNT),
    )

    /** One window's breakdown; null when its read failed. */
    private data class WindowResult(val window: TrackingWindow, val breakdown: CategoryBreakdownResult?)

    private data class Lookups(val categories: List<Category>, val currencies: List<Currency>)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val WINDOW_KEY = "spendingWindow"

        /** Sub-categories named under a row, as on Tracking. */
        const val PREVIEW_COUNT = 2
    }
}
