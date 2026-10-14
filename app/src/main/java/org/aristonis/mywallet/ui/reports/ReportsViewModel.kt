package org.aristonis.mywallet.ui.reports

import android.net.Uri
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
import org.aristonis.mywallet.domain.model.SubCategoryTotal
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.usecase.CategoryBreakdownResult
import org.aristonis.mywallet.domain.usecase.ComputeCategoryBreakdown
import org.aristonis.mywallet.domain.usecase.ExportTrackingCsv
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
import org.aristonis.mywallet.ui.CategoryLabel
import org.aristonis.mywallet.ui.format.display
import org.aristonis.mywallet.ui.label
import org.aristonis.mywallet.ui.load.ReadAttempts
import org.aristonis.mywallet.ui.load.loadFailure
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.settings.DocumentIo
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.aristonis.mywallet.ui.window.TrackingWindowHolder
import java.io.OutputStream
import java.time.LocalDate
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

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

    /** The first sub-categories by name, shown on the collapsed row. Never the unlabelled remainder. */
    val previewParts: List<CategoryLabel>
        get() = subCategories.filter { it.id != null }.take(PREVIEW_COUNT).map { it.label }

    private companion object {
        const val PREVIEW_COUNT = 2
    }
}

/** One part of a category's total. A null [id] is the share recorded without a sub-category. */
data class SubCategoryRow(val id: Long?, val label: CategoryLabel, val totalDisplay: String)

/**
 * The report body. A missing rate is a first-class value (not a spinner and not a wrong number), so
 * the screen shows a prompt; because the underlying use-case emits it as a value the flow stays live
 * and this resolves to [Ready] the moment the rate is set. Mirrors Home's summary state.
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
    ) : ReportsData {
        /** Nothing was counted in the window: no category on either side. */
        val isEmpty: Boolean get() = incomeCategories.isEmpty() && expenseCategories.isEmpty()
    }
}

/** What the Tracking screen renders: the span it covers, and the numbers for that span. */
data class ReportsUiState(
    val window: TrackingWindow,
    val data: ReportsData = ReportsData.Loading,
    /**
     * The window's entries could not be read. The screen says so instead of showing totals: zeros
     * would read as "nothing happened in these dates", which is not known. [data] stays
     * [ReportsData.Loading] then, as no numbers belong to this window.
     */
    val loadFailed: Boolean = false,
)

/**
 * Tracking: income / expense / net and both by-category breakdowns over a [TrackingWindow].
 *
 * The window is the single input. [TrackingWindowHolder] owns it, saves it so a recreated screen
 * shows the same dates, and keeps a current month current across midnight; its range re-drives the
 * breakdown through [flatMapLatest]. The breakdown carries the period's income / expense / net as
 * well, so each window reads its transactions once and the headline numbers always equal the sum of
 * the categories under them. Categories and currencies are joined in so a rename or a new rate
 * refreshes the report without the user touching anything. They do not depend on the dates, so they
 * are joined outside the window switch: stepping through months keeps one subscription to each
 * instead of reopening both on every tap.
 *
 * Converting, summing and formatting run on [defaultDispatcher], off the main thread, because all
 * time can cover thousands of transactions.
 *
 * A read that throws (a stored row that cannot be decoded) is caught on the window's own read and
 * becomes a failed value for that window, reported to [ErrorReporter]. Caught further down, it would
 * complete the whole chain and the date bar would stop working; caught there, other dates and
 * [retry] still read. The categories and currencies are caught the same way, inside the attempt, so
 * [retry] reads them again too.
 *
 * The breakdown sums category legs, which never open a whole entry, so an entry of a kind the app
 * cannot read would be left out of every total. The ledger's health is read beside it, outside the
 * window switch: while an entry cannot be read the screen shows the cannot-be-read state for every
 * window, so choosing other dates cannot hide it, and [retry] reads the health again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val computeCategoryBreakdown: ComputeCategoryBreakdown,
    private val observeLedgerHealth: ObserveLedgerHealth,
    categories: CategoryRepository,
    currencies: CurrencyRepository,
    private val moneyFormatter: MoneyFormatter,
    today: TodayProvider,
    savedStateHandle: SavedStateHandle,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    private val errors: ErrorReporter,
    exportTrackingCsv: ExportTrackingCsv,
    documentIo: DocumentIo,
) : ViewModel() {

    private val holder = TrackingWindowHolder(
        savedState = savedStateHandle,
        key = WINDOW_KEY,
        today = today,
        defaultWindow = { TrackingWindow.Period(TrackingPeriod.MONTH, it) },
    )

    private val csvExport = TrackingCsvExport(
        viewModelScope, defaultDispatcher, observeLedgerHealth, exportTrackingCsv, documentIo, errors, savedStateHandle,
    )

    /** Separate from report reads so an export never replaces or freezes the visible totals. */
    val exportState: StateFlow<ReportsExportState> = csvExport.state

    /** Captures the window now, before asynchronous preflight and the document picker. */
    fun prepareExport() = csvExport.prepare(holder.window.value.range)

    /** Consumes Ready before launching SAF, avoiding a second picker after recomposition. */
    fun onExportPickerLaunched() = csvExport.pickerLaunched()

    fun onExportPickerFailed(error: Exception) = csvExport.pickerFailed(error)

    /** A cancelled destination clears the private artifact; a chosen destination writes it once. */
    fun completeExport(uri: Uri?) = csvExport.complete(uri)

    /** URI-free stream seam for testing the artifact and the document-close outcome. */
    internal suspend fun runCsvWrite(open: suspend (suspend (OutputStream) -> Unit) -> Unit) = csvExport.write(open)

    fun consumeExportResult() = csvExport.consumeResult()

    /** The date bar's callbacks, already wired to this screen's window. */
    val windowActions: DateWindowActions get() = holder.actions

    /** Starts the window's read and the lookups over on [retry]. */
    private val attempts = ReadAttempts()

    private val windowResults: Flow<WindowResults> =
        holder.window.flatMapLatest { current ->
            computeCategoryBreakdown(current.range)
                .map<CategoryBreakdownResult, WindowResults> { breakdown -> WindowResults(current, breakdown) }
                .catch { error ->
                    if (error is CancellationException) throw error
                    errors.report("Tracking for ${current.range} could not be read", error)
                    emit(WindowResults(current, breakdown = null))
                }
        }

    /**
     * Null when the categories or currencies could not be read. The catch ends only this attempt's
     * lookups: combine keeps the null as their last value, so the window's own read carries on and
     * the screen shows the failure instead of the app closing.
     */
    private val lookups: Flow<Lookups?> =
        combine(categories.observeAll(), currencies.observeAll()) { categoryList, currencyList ->
            Lookups(categoryList, currencyList)
        }
            .catch<Lookups?> { error ->
                if (error is CancellationException) throw error
                errors.report("Tracking could not read the categories or currencies", error)
                emit(null)
            }

    /**
     * Null while every entry can be read; the failure once one cannot, or once the health read itself
     * fails. Reported once, read again on [retry].
     */
    private val healthFailure: Flow<UiMessage?> = observeLedgerHealth.loadFailure(errors, screen = "Tracking")

    val state: StateFlow<ReportsUiState> =
        attempts.reading(loading = { ReportsUiState(window = holder.window.value) }) {
            combine(windowResults, lookups, healthFailure) { results, lookup, failure ->
                if (failure == null) stateFor(results, lookup) else ReportsUiState(window = results.window, loadFailed = true)
            }
        }
            // Everything above, the breakdown's conversion and the formatting in toData, runs off main.
            .flowOn(defaultDispatcher)
            .stateIn(
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

    /**
     * Reads the window in force and the lookups again, after a failed read. The dates stay as they
     * are, and the report shows as loading until the new read answers.
     */
    fun retry() = attempts.retry()

    private fun stateFor(results: WindowResults, lookup: Lookups?): ReportsUiState {
        val breakdown = results.breakdown
        if (breakdown == null || lookup == null) {
            return ReportsUiState(window = results.window, loadFailed = true)
        }
        return ReportsUiState(window = results.window, data = toData(breakdown, lookup))
    }

    private fun toData(breakdown: CategoryBreakdownResult, lookup: Lookups): ReportsData {
        val currencyList = lookup.currencies
        // Exhaustive matching (not casts) so adding a result variant later is a compile error.
        val totals = when (breakdown) {
            is CategoryBreakdownResult.MissingRate -> return ReportsData.MissingRate(breakdown.currencyCode)
            is CategoryBreakdownResult.Resolved -> breakdown.breakdown
        }
        val periodSummary = totals.summary
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

    /**
     * What the breakdown computed for one window, kept with the window it computed it for. A null
     * [breakdown] means the read failed: the failure is a value here so it belongs to that window alone.
     */
    private data class WindowResults(
        val window: TrackingWindow,
        val breakdown: CategoryBreakdownResult?,
    )

    /** The names and currencies the numbers are dressed in; the same for every window. */
    private data class Lookups(val categories: List<Category>, val currencies: List<Currency>)

    private companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val WINDOW_KEY = "trackingWindow"
    }
}
