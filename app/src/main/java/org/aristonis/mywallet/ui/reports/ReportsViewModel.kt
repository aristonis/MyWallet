package org.aristonis.mywallet.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.usecase.CategoryBreakdownResult
import org.aristonis.mywallet.domain.usecase.ComputeCategoryBreakdown
import org.aristonis.mywallet.domain.usecase.ComputePeriodSummary
import org.aristonis.mywallet.domain.usecase.PeriodSummaryResult
import org.aristonis.mywallet.ui.format.display
import javax.inject.Inject
import org.aristonis.mywallet.ui.label

/**
 * One row of the spending-by-category list: [id] is the category id (a stable list key — names are
 * not unique and unknown ids all render as a dash), [name] is already resolved for display, and
 * [totalDisplay] is the total pre-formatted per currency + locale.
 */
data class CategoryRow(val id: Long, val name: String, val totalDisplay: String)

/**
 * The report body. A missing rate is a first-class value (not a spinner and not a wrong number), so
 * the screen shows a prompt; because the underlying use-cases emit it as a value the flow stays live
 * and this resolves to [Ready] the moment the rate is set. Mirrors Home's [NetWorthState].
 */
sealed interface ReportsData {
    data object Loading : ReportsData
    data class MissingRate(val currencyCode: String) : ReportsData
    data class Ready(
        val incomeDisplay: String,
        val expenseDisplay: String,
        val netDisplay: String,
        val categories: List<CategoryRow>,
    ) : ReportsData
}

/** What the Reports screen renders. [selectedPeriod] is always known (drives the picker). */
data class ReportsUiState(
    val selectedPeriod: TrackingPeriod = TrackingPeriod.MONTH,
    val data: ReportsData = ReportsData.Loading,
)

/**
 * Reports: income / expense / net and spending-by-category over a selectable [TrackingPeriod].
 * The period is reactive — selecting one re-drives both use-cases (via [flatMapLatest]), re-reading
 * "today" each time so the range tracks the current day. The category breakdown is joined with the
 * categories stream to turn ids into names, so a rename refreshes the report on its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val computePeriodSummary: ComputePeriodSummary,
    private val computeCategoryBreakdown: ComputeCategoryBreakdown,
    private val categories: CategoryRepository,
    private val currencies: CurrencyRepository,
    private val moneyFormatter: MoneyFormatter,
    private val today: TodayProvider,
) : ViewModel() {

    private val period = MutableStateFlow(TrackingPeriod.MONTH)

    val state: StateFlow<ReportsUiState> =
        period.flatMapLatest { selected ->
            val reference = today.today() // read per period change, not once at injection
            combine(
                computePeriodSummary(selected, reference),
                computeCategoryBreakdown(selected, reference),
                categories.observeAll(),
                currencies.observeAll(),
            ) { summary, breakdown, categoryList, currencyList ->
                ReportsUiState(
                    selectedPeriod = selected,
                    data = toData(summary, breakdown, categoryList, currencyList),
                )
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            ReportsUiState(),
        )

    fun selectPeriod(newPeriod: TrackingPeriod) {
        period.value = newPeriod
    }

    private fun toData(
        summary: PeriodSummaryResult,
        breakdown: CategoryBreakdownResult,
        categoryList: List<Category>,
        currencyList: List<Currency>,
    ): ReportsData {
        // Exhaustive matching (not casts) so adding a result variant later is a compile error; a
        // missing rate from EITHER use-case surfaces as the prompt.
        val periodSummary = when (summary) {
            is PeriodSummaryResult.MissingRate -> return ReportsData.MissingRate(summary.currencyCode)
            is PeriodSummaryResult.Resolved -> summary.summary
        }
        val totals = when (breakdown) {
            is CategoryBreakdownResult.MissingRate -> return ReportsData.MissingRate(breakdown.currencyCode)
            is CategoryBreakdownResult.Resolved -> breakdown.totals
        }
        // Sort by the exact Money (all totals are in the base currency) BEFORE formatting to strings.
        val rows = totals
            .sortedByDescending { it.total } // biggest spend first
            .map {
                CategoryRow(
                    id = it.categoryId,
                    name = categoryName(it.categoryId, categoryList),
                    totalDisplay = moneyFormatter.display(it.total, currencyList),
                )
            }

        return ReportsData.Ready(
            incomeDisplay = moneyFormatter.display(periodSummary.income, currencyList),
            expenseDisplay = moneyFormatter.display(periodSummary.expense, currencyList),
            netDisplay = moneyFormatter.display(periodSummary.net, currencyList),
            categories = rows,
        )
    }

    // A deleted/unknown category id degrades to a dash instead of dropping the row (defensive read).
    private fun categoryName(id: Long, categories: List<Category>): String =
        categories.firstOrNull { it.id == id }?.label() ?: MISSING

    private companion object {
        private const val MISSING = "—"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
