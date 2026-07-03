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
import org.aristonis.mywallet.ui.format.display
import java.time.LocalDate
import javax.inject.Inject

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
    val accountName: String,
    val destAccountName: String?,
    val categoryName: String?,
    val amount: Money,
    val destAmount: Money?,
    val amountDisplay: String,
    val destAmountDisplay: String?,
    val note: String?,
)

/** Immutable snapshot the transactions list renders from. */
data class TransactionsUiState(
    val rows: List<TransactionRow> = emptyList(),
    val isLoading: Boolean = true,
)

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
            buildRows(transactionList, accountList, categoryList, currencyList)
        }
            .onEach { rows -> _state.update { it.copy(rows = rows, isLoading = false) } }
            .launchIn(viewModelScope)
    }

    private fun buildRows(
        transactions: List<Transaction>,
        accounts: List<Account>,
        categories: List<Category>,
        currencies: List<Currency>,
    ): List<TransactionRow> = transactions
        .sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.id })
        .map { it.toRow(accounts, categories, currencies) }

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
                categoryName = categoryName(categoryId, categories),
                amount = amount, destAmount = null,
                amountDisplay = moneyFormatter.display(amount, currencies), destAmountDisplay = null,
                note = note,
            )

            is Transaction.Expense -> TransactionRow(
                id = id, date = date, type = TransactionRowType.EXPENSE,
                accountName = accountName(accountId, accounts),
                destAccountName = null,
                categoryName = categoryName(categoryId, categories),
                amount = amount, destAmount = null,
                amountDisplay = moneyFormatter.display(amount, currencies), destAmountDisplay = null,
                note = note,
            )

            is Transaction.Transfer -> TransactionRow(
                id = id, date = date, type = TransactionRowType.TRANSFER,
                accountName = accountName(sourceAccountId, accounts),
                destAccountName = accountName(destAccountId, accounts),
                categoryName = null,
                amount = sourceAmount, destAmount = destAmount,
                amountDisplay = moneyFormatter.display(sourceAmount, currencies),
                destAmountDisplay = moneyFormatter.display(destAmount, currencies),
                note = note,
            )
        }

    // A deleted/unknown id degrades to a dash instead of crashing the whole list (defensive read).
    private fun accountName(id: Long, accounts: List<Account>): String =
        accounts.firstOrNull { it.id == id }?.name ?: MISSING

    private fun categoryName(id: Long, categories: List<Category>): String =
        categories.firstOrNull { it.id == id }?.name ?: MISSING

    private companion object {
        private const val MISSING = "—"
    }
}
