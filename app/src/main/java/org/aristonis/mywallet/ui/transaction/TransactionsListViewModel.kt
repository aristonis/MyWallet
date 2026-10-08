package org.aristonis.mywallet.ui.transaction

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.TransactionRepository
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.ui.format.display
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.aristonis.mywallet.ui.window.TrackingWindowHolder
import java.time.LocalDate
import javax.inject.Inject
import org.aristonis.mywallet.ui.CategoryLabel
import org.aristonis.mywallet.ui.label

/** Which kind a display row is — lets the screen pick the sign/icon without re-deriving it. */
enum class TransactionRowType { INCOME, EXPENSE, TRANSFER }

/**
 * A display-ready transaction row: ids already resolved to names, amounts pre-formatted per currency
 * + locale in [amountDisplay]/[destAmountDisplay]. The raw [Money] stays too so tests assert on exact
 * values and the screen keeps applying its own +/−/→ sign prefixes. Transfer rows carry BOTH legs.
 */
data class TransactionRow(
    val id: Long,
    val date: LocalDate,
    val type: TransactionRowType,
    /** Null when the id resolved to nothing — the screen decides how to show a gap. */
    val accountName: String?,
    val destAccountName: String?,
    val categoryLabel: CategoryLabel?,
    val amount: Money,
    val destAmount: Money?,
    val amountDisplay: String,
    val destAmountDisplay: String?,
    val note: String?,
)

/** How a day reads against the device's current date. */
enum class DayRelation { TODAY, YESTERDAY, EARLIER }

/**
 * One day of history. The list is read a day at a time — "what did I spend today" — so the day is
 * part of the structure rather than a field repeated on every row.
 */
data class TransactionSection(
    val date: LocalDate,
    val relation: DayRelation,
    val rows: List<TransactionRow>,
)

/**
 * Everything, before the view-model has said otherwise. All time ignores its anchor, so the day it is
 * pinned to here never reaches the screen.
 */
private val EVERYTHING: TrackingWindow = TrackingWindow.Period(TrackingPeriod.ALL_TIME, LocalDate.MIN)

/** Immutable snapshot the transactions list renders from. */
data class TransactionsUiState(
    val sections: List<TransactionSection> = emptyList(),
    val isLoading: Boolean = true,
    /** The dates the list is narrowed to; the [sections] always belong to this window. */
    val window: TrackingWindow = EVERYTHING,
) {
    /** Every row, newest first, ignoring day boundaries. Derived so the two can never disagree. */
    val rows: List<TransactionRow> get() = sections.flatMap { it.rows }

    /**
     * Whether the list is unfiltered. An empty list means "nothing recorded yet" only then; under a
     * narrower window it means "nothing in these dates", and the screen must not confuse the two.
     */
    val showsEverything: Boolean
        get() = (window as? TrackingWindow.Period)?.period == TrackingPeriod.ALL_TIME
}

/** The transactions of one window, kept with the window they were read for. */
private data class WindowedTransactions(val window: TrackingWindow, val transactions: List<Transaction>)

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
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionsListViewModel @Inject constructor(
    transactions: TransactionRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    currencies: CurrencyRepository,
    private val moneyFormatter: MoneyFormatter,
    private val today: TodayProvider,
    savedStateHandle: SavedStateHandle,
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

    private val _state = MutableStateFlow(TransactionsUiState(window = holder.window.value))
    val state: StateFlow<TransactionsUiState> = _state.asStateFlow()

    /** The date bar's callbacks, already wired to this screen's window. */
    val windowActions: DateWindowActions get() = holder.actions

    init {
        val windowed: Flow<WindowedTransactions> = holder.window.flatMapLatest { window ->
            transactions.observeBetween(window.range).map { WindowedTransactions(window, it) }
        }
        combine(
            windowed,
            accounts.observeAll(),
            categories.observeAll(),
            currencies.observeAll(),
            currentDay,
        ) { read, accountList, categoryList, currencyList, day ->
            read.window to buildSections(read.transactions, Lookups(accountList, categoryList, currencyList), day)
        }
            .onEach { (window, sections) ->
                _state.update { it.copy(sections = sections, window = window, isLoading = false) }
            }
            .launchIn(viewModelScope)
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
     * Newest first, then grouped by day. The sort happens before the grouping so a section's rows
     * stay in the order the flat list would have had, and so the day order falls out of it.
     */
    private fun buildSections(
        transactions: List<Transaction>,
        lookups: Lookups,
        day: LocalDate,
    ): List<TransactionSection> =
        transactions
            .sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.id })
            .map { it.toRow(lookups.accounts, lookups.categories, lookups.currencies) }
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

    private fun Transaction.toRow(
        accounts: List<Account>,
        categories: List<Category>,
        currencies: List<Currency>,
    ): TransactionRow =
        when (this) { // exhaustive over the sealed Transaction — no `else`
            is Transaction.Income -> TransactionRow(
                id = id, date = date, type = TransactionRowType.INCOME,
                accountName = accountName(accountId, accounts),
                destAccountName = null,
                categoryLabel = categoryLabel(categoryId, categories),
                amount = amount, destAmount = null,
                amountDisplay = moneyFormatter.display(amount, currencies), destAmountDisplay = null,
                note = note,
            )

            is Transaction.Expense -> TransactionRow(
                id = id, date = date, type = TransactionRowType.EXPENSE,
                accountName = accountName(accountId, accounts),
                destAccountName = null,
                categoryLabel = categoryLabel(categoryId, categories),
                amount = amount, destAmount = null,
                amountDisplay = moneyFormatter.display(amount, currencies), destAmountDisplay = null,
                note = note,
            )

            is Transaction.Transfer -> TransactionRow(
                id = id, date = date, type = TransactionRowType.TRANSFER,
                accountName = accountName(sourceAccountId, accounts),
                destAccountName = accountName(destAccountId, accounts),
                categoryLabel = null,
                amount = sourceAmount, destAmount = destAmount,
                amountDisplay = moneyFormatter.display(sourceAmount, currencies),
                destAmountDisplay = moneyFormatter.display(destAmount, currencies),
                note = note,
            )
        }

    // A deleted/unknown id degrades to a gap instead of crashing the whole list (defensive read).
    // Nothing here decides what a gap looks like — that would be copy, and copy lives in the UI.
    private fun accountName(id: Long, accounts: List<Account>): String? =
        accounts.firstOrNull { it.id == id }?.name

    private fun categoryLabel(id: Long, categories: List<Category>): CategoryLabel =
        categories.firstOrNull { it.id == id }?.label() ?: CategoryLabel.Unknown

    /** The names and currencies the rows are dressed in; the same for every window. */
    private data class Lookups(
        val accounts: List<Account>,
        val categories: List<Category>,
        val currencies: List<Currency>,
    )

    private companion object {
        private const val WINDOW_KEY = "transactions.window"
    }
}
