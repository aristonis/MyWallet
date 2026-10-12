package org.aristonis.mywallet.ui.transaction

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
import org.aristonis.mywallet.di.DefaultDispatcher
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.ui.load.ReadAttempts
import org.aristonis.mywallet.ui.load.loadFailure
import org.aristonis.mywallet.ui.load.toLoadFailure
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.aristonis.mywallet.ui.window.TrackingWindowHolder
import java.time.LocalDate
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Where the read of one window stands, kept with the window it is for. A failure is a value here so
 * it belongs to that window alone.
 */
private sealed interface WindowedTransactions {
    val window: TrackingWindow

    /** [failure] is what the user is told: an unreadable row, or the generic failure. */
    data class Failed(override val window: TrackingWindow, val failure: UiMessage) : WindowedTransactions

    /** The window's pages loaded so far. */
    data class Read(override val window: TrackingWindow, val pages: LoadedPages) : WindowedTransactions
}

/**
 * How many pages the user has scrolled into, and in which window. Any other window opens on one
 * page; a null [window] is the state after the dates were changed.
 */
private data class PagesWanted(val window: TrackingWindow?, val pages: Int) {
    fun pagesFor(current: TrackingWindow): Int = if (current == window) pages else 1

    companion object {
        val FIRST = PagesWanted(window = null, pages = 1)
    }
}

/**
 * The transactions history. The window's transactions are joined with the accounts, categories and
 * currencies streams so every id becomes a name; it re-emits whenever any of them change (record a
 * transaction, rename an account) and the list refreshes itself. Newest-first is enforced here so it
 * holds regardless of what order the repository returns.
 *
 * The window opens on all time, so the history reads as it always has until the user narrows it.
 * [TrackingWindowHolder] owns it and saves it, so a recreated screen keeps the dates it was showing.
 * Only the transactions depend on the dates: the lookups sit outside the window switch, so moving
 * through months keeps one subscription to each instead of reopening them on every tap.
 *
 * The list loads in pages: a window opens on its newest [PAGE_SIZE] entries, and [loadMore] appends
 * the next page when the user scrolls to the end. [TransactionPages] does the reading: a next page
 * is one read after the last row shown, and a save, edit or delete re-reads the loaded pages
 * together, so the rows stay an unbroken run from the newest entry.
 *
 * The rows come from whole entries, but an entry of a kind the app cannot read is in no page, so
 * the ledger's health is read beside them. While it reports such an entry the list shows the
 * cannot-be-read state for every window; [retry] reads the health again. A health read that fails
 * for another reason shows the generic failure instead.
 *
 * Building the sections (sorting, grouping, resolving names, formatting money) runs on
 * [defaultDispatcher], off the main thread. The list only reads while its screen watches it: a few
 * seconds after the tab is hidden the reads stop, and they pick up the latest data when it is shown
 * again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionsListViewModel @Inject constructor(
    entries: EntryRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    currencies: CurrencyRepository,
    settings: SettingsRepository,
    observeLedgerHealth: ObserveLedgerHealth,
    private val moneyFormatter: MoneyFormatter,
    private val today: TodayProvider,
    savedStateHandle: SavedStateHandle,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    private val errors: ErrorReporter,
) : ViewModel() {

    private val holder = TrackingWindowHolder(
        savedState = savedStateHandle,
        key = WINDOW_KEY,
        today = today,
        defaultWindow = { TrackingWindow.Period(TrackingPeriod.ALL_TIME, it) },
    )

    /**
     * The day "Today" and "Yesterday" are measured against. It is a stream rather than a read inside
     * the section building because all time never moves when the day changes: without it, a list
     * left open overnight would keep calling yesterday "Today".
     */
    private val currentDay = MutableStateFlow(today.today())

    /** Starts the window's read and the lookups over on [retry]. */
    private val attempts = ReadAttempts()

    /**
     * The pages asked for. Kept here rather than inside the reads, so a screen that comes back after
     * its reads stopped finds the same pages loaded, at the place it was scrolled to.
     */
    private val pagesWanted = MutableStateFlow(PagesWanted.FIRST)

    private val pages = TransactionPages(entries, PAGE_SIZE)

    /**
     * The window's pages. Only the page count for this window is followed, so a page asked for
     * elsewhere never touches this read.
     *
     * A read that throws (a stored row that cannot be decoded) is caught here, on the window's own
     * read, and becomes a failed value for that window. Caught any further down, the whole chain
     * would complete and the date bar would stop doing anything until the screen was rebuilt; caught
     * here, only this read ends, and choosing other dates, [retry], or the screen coming back after
     * its reads stopped reads again.
     */
    private val windowed: Flow<WindowedTransactions> =
        holder.window.flatMapLatest { window ->
            val wanted = pagesWanted.map { it.pagesFor(window) }.distinctUntilChanged()
            pages.observe(window.range, wanted)
                .map<LoadedPages, WindowedTransactions> { loaded -> WindowedTransactions.Read(window, loaded) }
                .catch { error ->
                    if (error is CancellationException) throw error
                    errors.report("Transactions for ${window.range} could not be read", error)
                    emit(WindowedTransactions.Failed(window, error.toLoadFailure()))
                }
        }

    /**
     * Null while every entry can be read; the failure once one cannot, or once the health read itself
     * fails. Reported once, read again on [retry].
     */
    private val healthFailure: Flow<UiMessage?> = observeLedgerHealth.loadFailure(errors, screen = "Transactions")

    /**
     * Null when the accounts, categories or currencies could not be read. The catch ends only this
     * attempt's lookups: combine keeps the null as their last value, so the window's own read carries
     * on and the screen shows the failure instead of the app closing. [retry] reads them again.
     */
    private val lookups: Flow<RowLookups?> =
        combine(
            accounts.observeAll(),
            categories.observeAll(),
            currencies.observeAll(),
            settings.observeOrNull(),
        ) { accountList, categoryList, currencyList, currentSettings ->
            RowLookups(
                accountsById = accountList.associateBy { it.id },
                categoriesById = categoryList.associateBy { it.id },
                currencies = currencyList,
                baseCurrencyCode = currentSettings?.baseCurrencyCode,
            )
        }
            .catch<RowLookups?> { error ->
                if (error is CancellationException) throw error
                errors.report("Transactions could not read the accounts, categories, currencies or settings", error)
                emit(null)
            }

    /** The date of an entry saved outside the window, until the user acts on it or lets it go. */
    private val savedOutside = MutableStateFlow<LocalDate?>(null)

    /**
     * The sections are built inside the combine, upstream of [flowOn], so the building happens on
     * [defaultDispatcher]; only the finished state is handed back. Before the first emission the state
     * is loading on the saved window, so the date bar shows the right dates from the start.
     *
     * The saved-entry message and the next page being read join the finished state downstream of the
     * building, so showing or clearing either copies one value instead of sorting and grouping the
     * whole window again.
     *
     * There is deliberately no catch at the end of this chain: a catch completes the flow it guards,
     * and the list would freeze on the failure. Failures are turned into state where they happen, in
     * [windowed], in [lookups], in [healthFailure] and in [stateFor].
     */
    val state: StateFlow<TransactionsUiState> =
        attempts.reading(loading = { TransactionsUiState(window = holder.window.value) }) {
            combine(windowed, lookups, healthFailure, currentDay) { read, lookup, failure, day ->
                if (failure == null) stateFor(read, lookup, day) else failedState(read.window, failure)
            }
        }
            .flowOn(defaultDispatcher)
            .combine(savedOutside) { built, saved -> built.copy(savedOutsideWindow = saved) }
            .combine(pagesWanted) { built, wanted ->
                // Rows short of the pages asked for, while more exist, are the next page on its way.
                built.copy(isLoadingMore = built.hasMore && built.rowCount < wanted.pagesFor(built.window) * PAGE_SIZE)
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                TransactionsUiState(window = holder.window.value),
            )

    /** The date bar's callbacks, already wired to this screen's window. */
    val windowActions: DateWindowActions = holder.actions.let { moves ->
        DateWindowActions(
            onSelectPeriod = { movingDates { moves.onSelectPeriod(it) } },
            onStep = { movingDates { moves.onStep(it) } },
            onJumpTo = { movingDates { moves.onJumpTo(it) } },
            onSelectRange = { first, second -> movingDates { moves.onSelectRange(first, second) } },
            onClearRange = { movingDates { moves.onClearRange() } },
        )
    }

    /**
     * Runs a date change the user asked for. "Outside these dates" would then describe something
     * else, so the message goes. When the dates really change they open on one page, even when they
     * equal dates the user had scrolled deep into earlier; a tap that leaves them as they are keeps
     * the pages loaded.
     */
    private inline fun movingDates(move: () -> Unit) {
        val before = holder.window.value
        savedOutside.value = null
        move()
        if (holder.window.value != before) pagesWanted.value = PagesWanted.FIRST
    }

    /**
     * Asks for the next page; the screen calls this when its last row comes into view. Does nothing
     * while the list is loading or failed, when nothing is left, or while a page is already on its
     * way: the target is the page after the rows shown, so repeated calls before it arrives ask for
     * the same page once.
     *
     * While the dates are changing, the rows on screen still belong to the old dates and say nothing
     * about the new ones. The request is then kept for the new dates as their second page, loaded
     * once their first page is in and only if they hold more, so a scroll made in that moment is not
     * lost.
     */
    fun loadMore() {
        val shown = state.value
        val window = holder.window.value
        if (shown.window != window) {
            pagesWanted.update { wanted -> PagesWanted(window, maxOf(wanted.pagesFor(window), 2)) }
            return
        }
        if (!shown.hasMore || shown.isLoading || shown.loadFailed) return
        val next = shown.rowCount / PAGE_SIZE + 1
        pagesWanted.update { wanted -> PagesWanted(window, maxOf(wanted.pagesFor(window), next)) }
    }

    /**
     * The screen is back in view. A window that was following today moves along with it, and the
     * day labels are measured against the new date even when the window itself stays put.
     */
    fun onScreenStart() {
        holder.refreshToday()
        currentDay.value = today.today()
    }

    /**
     * An entry dated [date] was just saved from this tab. The window in force decides, not the last
     * state shown: the user may have changed the dates a moment ago, before the list was rebuilt for
     * them. All time holds every date, so it never has anything to say. Each save replaces the last
     * message, so a save inside the dates also clears one the user never answered.
     */
    fun onEntrySaved(date: LocalDate) {
        savedOutside.value = date.takeUnless { it in holder.window.value.range }
    }

    /**
     * Moves the window onto the saved entry and drops the message. The period kind is kept; a custom
     * range has no neighbour that would hold the entry, so it gives way to the entry's month.
     */
    fun showSavedEntry() {
        val date = savedOutside.value ?: return
        movingDates { holder.jumpTo(date) }
    }

    /**
     * Reads the window in force and the lookups again, after a failed read. The dates stay as they
     * are, and the list shows as loading until the new read answers.
     */
    fun retry() = attempts.retry()

    /** The user let the message go; the window stays where they put it. */
    fun dismissSavedEntryMessage() {
        savedOutside.value = null
    }

    /**
     * The state for one emission. Building is guarded too: a row the formatter cannot handle would
     * otherwise throw out of the combine and take the list down with it. The guard covers this one
     * emission only, so the next change of data or dates builds again. Cancellation is not a failure
     * and is passed on. Null [lookups] means they could not be read, which fails the list too: rows
     * without names or currencies would misstate what was recorded.
     */
    private fun stateFor(read: WindowedTransactions, lookups: RowLookups?, day: LocalDate): TransactionsUiState {
        val served = when (read) {
            is WindowedTransactions.Failed -> return failedState(read.window, read.failure)
            is WindowedTransactions.Read -> read.pages
        }
        if (lookups == null) return failedState(read.window, UiMessage.Unexpected)
        return try {
            TransactionsUiState(
                sections = buildSections(served.transactions, lookups, day),
                isLoading = false,
                window = read.window,
                hasMore = served.hasMore,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors.report("Transactions for ${read.window.range} could not be listed", e)
            failedState(read.window, UiMessage.Unexpected)
        }
    }

    private fun failedState(window: TrackingWindow, failure: UiMessage) =
        TransactionsUiState(isLoading = false, window = window, loadFailed = true, loadFailure = failure)

    /**
     * Newest first, then grouped by day. The sort happens before the grouping so a section's rows
     * stay in the order the flat list would have had, and so the day order falls out of it.
     */
    private fun buildSections(
        transactions: List<Transaction>,
        lookups: RowLookups,
        day: LocalDate,
    ): List<TransactionSection> =
        transactions
            .sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.id })
            .map { it.toRow(lookups, moneyFormatter) }
            .groupBy { it.date }
            .map { (date, rows) -> TransactionSection(date, date.relationTo(day), rows) }

    /**
     * A date after today stays [DayRelation.EARLIER]: a transaction can be recorded ahead of time,
     * and calling a future day "today" would misdate it on screen.
     */
    private fun LocalDate.relationTo(day: LocalDate): DayRelation = when (this) {
        day -> DayRelation.TODAY
        day.minusDays(1) -> DayRelation.YESTERDAY
        else -> DayRelation.EARLIER
    }

    private companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val WINDOW_KEY = "transactions.window"

        /** Entries per page: about two screens of history, small enough to read quickly. */
        private const val PAGE_SIZE = 100
    }
}
