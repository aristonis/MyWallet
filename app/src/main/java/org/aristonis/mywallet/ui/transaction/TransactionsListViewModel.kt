package org.aristonis.mywallet.ui.transaction

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
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.TransactionRepository
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.ui.format.display
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

/** Immutable snapshot the transactions list renders from. */
data class TransactionsUiState(
    val sections: List<TransactionSection> = emptyList(),
    val isLoading: Boolean = true,
) {
    /** Every row, newest first, ignoring day boundaries. Derived so the two can never disagree. */
    val rows: List<TransactionRow> get() = sections.flatMap { it.rows }
}

/**
 * The transactions history. A 3-way `combine` joins the transactions stream with the accounts and
 * categories streams so every id becomes a name; it re-emits whenever any of the three change (record
 * a transaction, rename an account) and the list refreshes itself. Newest-first is enforced here so it
 * holds regardless of what order the repository returns.
 */
@HiltViewModel
class TransactionsListViewModel @Inject constructor(
    transactions: TransactionRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    currencies: CurrencyRepository,
    private val moneyFormatter: MoneyFormatter,
    private val today: TodayProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(TransactionsUiState())
    val state: StateFlow<TransactionsUiState> = _state.asStateFlow()

    init {
        combine(
            transactions.observeAll(),
            accounts.observeAll(),
            categories.observeAll(),
            currencies.observeAll(),
        ) { transactionList, accountList, categoryList, currencyList ->
            buildSections(transactionList, accountList, categoryList, currencyList)
        }
            .onEach { sections -> _state.update { it.copy(sections = sections, isLoading = false) } }
            .launchIn(viewModelScope)
    }

    /**
     * Newest first, then grouped by day. The sort happens before the grouping so a section's rows
     * stay in the order the flat list would have had, and so the day order falls out of it.
     */
    private fun buildSections(
        transactions: List<Transaction>,
        accounts: List<Account>,
        categories: List<Category>,
        currencies: List<Currency>,
    ): List<TransactionSection> {
        val currentDay = today.today()
        return transactions
            .sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.id })
            .map { it.toRow(accounts, categories, currencies) }
            .groupBy { it.date }
            .map { (date, rows) -> TransactionSection(date, date.relationTo(currentDay), rows) }
    }

    /**
     * A date after today stays [DayRelation.EARLIER]: a transaction can be recorded ahead of time,
     * and calling a future day "today" would misdate it on screen.
     */
    private fun LocalDate.relationTo(currentDay: LocalDate): DayRelation = when (this) {
        currentDay -> DayRelation.TODAY
        currentDay.minusDays(1) -> DayRelation.YESTERDAY
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
}
