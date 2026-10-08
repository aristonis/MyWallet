package org.aristonis.mywallet.ui.reports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryTotal
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.SubCategoryTotal
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.usecase.CategoryBreakdownResult
import org.aristonis.mywallet.domain.usecase.ComputeCategoryBreakdown
import org.aristonis.mywallet.domain.usecase.ComputePeriodSummary
import org.aristonis.mywallet.domain.usecase.PeriodSummaryResult
import org.aristonis.mywallet.ui.CategoryLabel
import org.aristonis.mywallet.ui.format.display
import org.aristonis.mywallet.ui.label
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.aristonis.mywallet.ui.window.TrackingWindowHolder
import java.time.LocalDate
import javax.inject.Inject

/**
 * One category in a by-category list. [id] is the category id, used as the list key because names
 * are not unique and every unknown id renders as the same dash. [subCategories] keep the domain's
 * order, biggest first, with the unlabelled remainder among them.
 */
data class CategoryRow(
    val id: Long,
    val label: CategoryLabel,
    val totalDisplay: String,
    val subCategories: List<SubCategoryRow>,
) {
    /**
     * Whether opening the row would show anything new. A category whose only part is its own
     * remainder would open onto a copy of itself, so it offers no toggle.
     */
    val canExpand: Boolean get() = subCategories.any { it.id != null }
}

/** One part of a category's total. A null [id] is the share recorded without a sub-category. */
data class SubCategoryRow(val id: Long?, val label: CategoryLabel, val totalDisplay: String)

/**
 * The report body. A missing rate is a first-class value (not a spinner and not a wrong number), so
 * the screen shows a prompt; because the underlying use-cases emit it as a value the flow stays live
 * and this resolves to [Ready] the moment the rate is set. Mirrors Home's net-worth state.
 */
sealed interface ReportsData {
    data object Loading : ReportsData
    data class MissingRate(val currencyCode: String) : ReportsData
    data class Ready(
        val incomeDisplay: String,
        val expenseDisplay: String,
        val netDisplay: String,
        val incomeCategories: List<CategoryRow>,
        val expenseCategories: List<CategoryRow>,
    ) : ReportsData
}

/** What the Tracking screen renders: the span it covers, and the numbers for that span. */
data class ReportsUiState(
    val window: TrackingWindow,
    val data: ReportsData = ReportsData.Loading,
)

/**
 * Tracking: income / expense / net and both by-category breakdowns over a [TrackingWindow].
 *
 * The window is the single input. [TrackingWindowHolder] owns it, saves it so a recreated screen
 * shows the same dates, and keeps a current month current across midnight; its range re-drives both
 * use cases through [flatMapLatest]. Categories and currencies are joined in so a rename or a new rate refreshes the
 * report without the user touching anything. They do not depend on the dates, so they are joined
 * outside the window switch: stepping through months keeps one subscription to each instead of
 * reopening both on every tap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val computePeriodSummary: ComputePeriodSummary,
    private val computeCategoryBreakdown: ComputeCategoryBreakdown,
    categories: CategoryRepository,
    currencies: CurrencyRepository,
    private val moneyFormatter: MoneyFormatter,
    today: TodayProvider,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val holder = TrackingWindowHolder(
        savedState = savedStateHandle,
        key = WINDOW_KEY,
        today = today,
        defaultWindow = { TrackingWindow.Period(TrackingPeriod.MONTH, it) },
    )

    /** The date bar's callbacks, already wired to this screen's window. */
    val windowActions: DateWindowActions get() = holder.actions

    private val windowResults: Flow<WindowResults> = holder.window.flatMapLatest { current ->
        combine(computePeriodSummary(current.range), computeCategoryBreakdown(current.range)) { summary, breakdown ->
            WindowResults(current, summary, breakdown)
        }
    }

    private val lookups: Flow<Lookups> = combine(categories.observeAll(), currencies.observeAll(), ::Lookups)

    val state: StateFlow<ReportsUiState> =
        combine(windowResults, lookups) { results, lookup ->
            ReportsUiState(window = results.window, data = toData(results, lookup))
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            ReportsUiState(window = holder.window.value),
        )

    /** Switches period, keeping today in view when the current window holds it. */
    fun selectPeriod(newPeriod: TrackingPeriod) = holder.selectPeriod(newPeriod)

    /** Moves to a neighbouring period; a custom range has none, so this leaves it alone. */
    fun step(steps: Long) = holder.step(steps)

    /** Shows the period that holds [anchor]. Leaving a custom range lands on its month. */
    fun jumpTo(anchor: LocalDate) = holder.jumpTo(anchor)

    /** Covers exactly the days between [first] and [second], in whichever order they were tapped. */
    fun selectRange(first: LocalDate, second: LocalDate) = holder.selectRange(first, second)

    /** Leaves a custom range for the month the user is living in, the screen's starting point. */
    fun clearRange() = holder.clearRange()

    /**
     * The screen is back in view. If it was showing the current month and the day has since moved
     * into the next one, it moves along; a month the user picked stays put.
     */
    fun onScreenStart() = holder.refreshToday()

    private fun toData(results: WindowResults, lookup: Lookups): ReportsData {
        val summary = results.summary
        val breakdown = results.breakdown
        val currencyList = lookup.currencies
        // Exhaustive matching (not casts) so adding a result variant later is a compile error; a
        // missing rate from EITHER use-case surfaces as the prompt.
        val periodSummary = when (summary) {
            is PeriodSummaryResult.MissingRate -> return ReportsData.MissingRate(summary.currencyCode)
            is PeriodSummaryResult.Resolved -> summary.summary
        }
        val totals = when (breakdown) {
            is CategoryBreakdownResult.MissingRate -> return ReportsData.MissingRate(breakdown.currencyCode)
            is CategoryBreakdownResult.Resolved -> breakdown.breakdown
        }
        val rows = CategoryRows(lookup.categories.associateBy { it.id }, currencyList)
        return ReportsData.Ready(
            incomeDisplay = moneyFormatter.display(periodSummary.income, currencyList),
            expenseDisplay = moneyFormatter.display(periodSummary.expense, currencyList),
            netDisplay = moneyFormatter.display(periodSummary.net, currencyList),
            incomeCategories = totals.income.map(rows::of),
            expenseCategories = totals.expense.map(rows::of),
        )
    }

    /**
     * Turns domain totals into display rows. The domain has already ordered them biggest first (ties
     * by id), so the order is kept as-is.
     */
    private inner class CategoryRows(
        private val byId: Map<Long, Category>,
        private val currencyList: List<Currency>,
    ) {
        fun of(total: CategoryTotal) = CategoryRow(
            id = total.categoryId,
            label = labelOf(total.categoryId),
            totalDisplay = moneyFormatter.display(total.total, currencyList),
            subCategories = total.subCategories.map(::subOf),
        )

        private fun subOf(total: SubCategoryTotal) = SubCategoryRow(
            id = total.subCategoryId,
            label = total.subCategoryId?.let(::labelOf) ?: CategoryLabel.NoSubCategory,
            totalDisplay = moneyFormatter.display(total.total, currencyList),
        )

        // A deleted/unknown id degrades to an Unknown label instead of dropping the row; the money
        // is still real. What that looks like on screen is the screen's business, not this one's.
        private fun labelOf(id: Long): CategoryLabel = byId[id]?.label() ?: CategoryLabel.Unknown
    }

    /** What the use cases computed for one window, kept with the window they computed it for. */
    private data class WindowResults(
        val window: TrackingWindow,
        val summary: PeriodSummaryResult,
        val breakdown: CategoryBreakdownResult,
    )

    /** The names and currencies the numbers are dressed in; the same for every window. */
    private data class Lookups(val categories: List<Category>, val currencies: List<Currency>)

    private companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val WINDOW_KEY = "trackingWindow"
    }
}
