package org.aristonis.mywallet.ui.account

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.di.DefaultDispatcher
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.model.AccountBalanceInBase
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.AccountEntryReader
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.DebtRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.usecase.GetAccountBalances
import org.aristonis.mywallet.domain.usecase.GetAccountBalancesInBase
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
import org.aristonis.mywallet.ui.format.display
import org.aristonis.mywallet.ui.load.ReadAttempts
import org.aristonis.mywallet.ui.load.loadFailure
import org.aristonis.mywallet.ui.load.toLoadFailure
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.navigation.ARG_ID
import org.aristonis.mywallet.ui.transaction.LoadedPages
import org.aristonis.mywallet.ui.transaction.RowLookups
import org.aristonis.mywallet.ui.transaction.TransactionPages
import org.aristonis.mywallet.ui.transaction.toRow
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads one account for its detail page: the current balance and credit status from the existing
 * balance use cases, and the account's own entries a page at a time from [AccountEntryReader]. The
 * account id arrives in [savedStateHandle] under the route's id argument.
 *
 * The balance comes from the leg projection, which never opens a whole entry, so an entry of a kind
 * the app cannot read would be left out of it. The ledger's health is read beside it, as on Home, and
 * a failure there shows the cannot-be-read state instead of a balance that misses a row.
 *
 * Entries are paged by [TransactionPages] over all time, newest first. A page read that throws (a
 * stored row that cannot be decoded) fails the whole attempt: a header beside a list with a hole in
 * it would state a history that does not add up to the balance. The catch ends only that attempt;
 * [retry] starts a fresh one, showing loading until it answers.
 */
@HiltViewModel
internal class AccountDetailViewModel @Inject constructor(
    private val getAccountBalances: GetAccountBalances,
    private val getAccountBalancesInBase: GetAccountBalancesInBase,
    accountEntries: AccountEntryReader,
    private val categories: CategoryRepository,
    private val currencies: CurrencyRepository,
    private val settings: SettingsRepository,
    private val debts: DebtRepository,
    private val observeLedgerHealth: ObserveLedgerHealth,
    private val moneyFormatter: MoneyFormatter,
    savedStateHandle: SavedStateHandle,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    private val errors: ErrorReporter,
) : ViewModel() {

    /** Null only when the route arrived without an id; that reads as an account that does not exist. */
    private val accountId: Long? = savedStateHandle.get<Long>(ARG_ID)

    private val attempts = ReadAttempts()

    /**
     * How many pages the user has scrolled into. Kept outside the reads so a screen that comes back
     * after its reads stopped, or a retry, finds the same pages asked for.
     */
    private val pagesWanted = MutableStateFlow(1)

    private val pages: TransactionPages? = accountId?.let { id ->
        TransactionPages(
            readPage = { range, after, limit -> accountEntries.observeAccountPage(id, range, after, limit) },
            pageSize = PAGE_SIZE,
        )
    }

    val state: StateFlow<AccountDetailUiState> =
        attempts.reading(loading = { AccountDetailUiState() }) { read() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AccountDetailUiState())

    /** Reads the account again after a failed read, showing loading until the new read answers. */
    fun retry() = attempts.retry()

    /**
     * Asks for the page after the rows shown; the screen calls this when the end of the list comes
     * into view. Does nothing while loading, after a failure, or when nothing is left. Repeated calls
     * before the page arrives ask for the same page once.
     */
    fun loadMore() {
        val shown = state.value
        if (shown.isLoading || shown.header == null || !shown.hasMore) return
        val next = shown.entries.size / PAGE_SIZE + 1
        pagesWanted.update { wanted -> maxOf(wanted, next) }
    }

    /**
     * One attempt. Building runs upstream of [flowOn], on [defaultDispatcher], since balances are
     * summed over the whole history and every row is formatted. The catch sits on this attempt only,
     * so the state flow itself never completes and [retry] can always read again.
     */
    private fun read(): Flow<AccountDetailUiState> {
        val id = accountId
        val entryPages = pages
        if (id == null || entryPages == null) return flowOf(NOT_FOUND)
        val balances = getAccountBalancesInBase(getAccountBalances())
        val lookups = combine(categories.observeAll(), currencies.observeAll(), settings.observeOrNull(), debts.observeAll()) {
            categoryList, currencyList, currentSettings, debtList ->
            PartialLookups(categoryList.associateBy { it.id }, currencyList, currentSettings?.baseCurrencyCode,
                debtList.associate { it.id to it.counterparty })
        }
        val healthFailure = observeLedgerHealth.loadFailure(errors, screen = "Account detail")
        val loaded = entryPages.observe(DateRange.ALL_TIME, pagesWanted)
        return combine(balances, lookups, healthFailure, loaded, pagesWanted) { rows, partial, failure, read, wanted ->
            stateFor(id, rows, partial, failure, read, wanted)
        }
            .flowOn(defaultDispatcher)
            .catch { error ->
                if (error is CancellationException) throw error
                errors.report("Account detail could not read account $id", error)
                emit(failedState(error.toLoadFailure()))
            }
    }

    /**
     * The state for one emission. A missing account is not found even while another row is
     * unreadable: the accounts themselves were read, and Retry could not bring this one back.
     * Building is guarded so a row the formatter cannot handle fails this emission alone.
     */
    private fun stateFor(
        id: Long,
        rows: List<AccountBalanceInBase>,
        partial: PartialLookups,
        failure: UiMessage?,
        read: LoadedPages,
        wanted: Int,
    ): AccountDetailUiState {
        val row = rows.firstOrNull { it.account.id == id } ?: return NOT_FOUND
        if (failure != null) return failedState(failure)
        return try {
            // Every account, archived ones included, so a transfer row still names its other side.
            val lookups = RowLookups(
                accountsById = rows.associate { it.account.id to it.account },
                categoriesById = partial.categoriesById,
                currencies = partial.currencies,
                baseCurrencyCode = partial.baseCurrencyCode,
                debtNamesById = partial.debtNamesById,
            )
            AccountDetailUiState(
                isLoading = false,
                header = row.toHeader(lookups),
                entries = read.transactions.map { it.toRow(lookups, moneyFormatter) },
                hasMore = read.hasMore,
                // Rows short of the pages asked for, while more exist, are the next page on its way.
                isLoadingMore = read.hasMore && read.transactions.size < wanted * PAGE_SIZE,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors.report("Account detail could not show account $id", e)
            failedState(UiMessage.Unexpected)
        }
    }

    /**
     * The native balance always leads. The converted line appears only when the currencies differ
     * and a rate exists; a missing rate is flagged instead of shown as zero. A credit account adds
     * what is owed, its limit and the credit left, all in the account's own currency.
     */
    private fun AccountBalanceInBase.toHeader(lookups: RowLookups): AccountDetailHeader {
        val display = { money: Money -> moneyFormatter.display(money, lookups.currencies) }
        return AccountDetailHeader(
            id = account.id,
            name = account.name,
            typeKey = account.typeKey,
            currencyCode = account.currencyCode,
            archived = account.archived,
            nativeDisplay = display(native),
            baseDisplay = base?.takeIf { it.currencyCode != native.currencyCode }?.let(display),
            missingRate = base == null,
            credit = credit?.let { status ->
                CreditDetail(
                    owedDisplay = display(status.owed),
                    limitDisplay = account.creditLimit?.let(display),
                    availableDisplay = status.available?.let(display),
                    overLimit = status.overLimit,
                )
            },
        )
    }

    private fun failedState(failure: UiMessage) = AccountDetailUiState(isLoading = false, loadFailure = failure)

    /** The row lookups apart from the accounts, which come with the balances. */
    private data class PartialLookups(
        val categoriesById: Map<Long, Category>,
        val currencies: List<Currency>,
        val baseCurrencyCode: String?,
        val debtNamesById: Map<Long, String>,
    )

    private companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Entries per page: a few screens of one account's history, small enough to read quickly. */
        private const val PAGE_SIZE = 50

        private val NOT_FOUND = AccountDetailUiState(isLoading = false, notFound = true)
    }
}
