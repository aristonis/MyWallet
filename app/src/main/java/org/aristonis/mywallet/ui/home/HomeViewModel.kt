package org.aristonis.mywallet.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.di.DefaultDispatcher
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.AccountBalanceInBase
import org.aristonis.mywallet.domain.model.CreditStatus
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.HomeSummary
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.usecase.ComputeHomeSummary
import org.aristonis.mywallet.domain.usecase.GetAccountBalances
import org.aristonis.mywallet.domain.usecase.GetAccountBalancesInBase
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
import org.aristonis.mywallet.ui.format.display
import org.aristonis.mywallet.ui.load.ReadAttempts
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Home hero. A missing rate becomes a warning, never a silently wrong total: [MissingRate] names
 * the currency whose rate is needed, and [Amounts] carries the finished figures (pocket, owed to me,
 * I owe, and the balance they add up to).
 */
sealed interface HomeSummaryState {
    data object Loading : HomeSummaryState
    data class Amounts(val pocketDisplay: String, val owedToMeDisplay: String, val iOweDisplay: String,
        val balanceDisplay: String) : HomeSummaryState
    data class MissingRate(val currencyCode: String) : HomeSummaryState
}

/**
 * One account card. Keeps the native/base [Money] so the screen can still decide which line to show
 * (missing rate vs. a converted line vs. same currency); the `*Display` strings are the amounts
 * pre-formatted per currency + locale so no formatting happens in Compose. A credit card also carries
 * its [credit] status with the owed, available and over-limit amounts.
 */
data class AccountRow(
    val account: Account,
    val native: Money,
    val base: Money?,
    val nativeDisplay: String,
    val baseDisplay: String?,
    val credit: CreditStatus? = null,
    val owedDisplay: String? = null,
    val availableDisplay: String? = null,
    val overLimitDisplay: String? = null,
)

/**
 * Home renders from this. Amounts arrive pre-formatted; the screen never touches a formatter.
 *
 * [isLoading] is true until the first read arrives, so the empty initial list is not mistaken for a
 * wallet with no accounts (a slow start would flash the "needs an account" prompt). [loadFailed] means
 * stored data could not be read: the screen says so instead of showing an empty wallet, which would
 * invite the user to create a first account they already have.
 */
data class HomeUiState(
    val summary: HomeSummaryState = HomeSummaryState.Loading,
    val accounts: List<AccountRow> = emptyList(),
    val baseCurrencyCode: String? = null,
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    /** Ids of debts whose remaining went below zero; Home warns and points at the Debts screen. */
    val debtsBelowZero: List<Long> = emptyList(),
)

/**
 * Home: the summary (pocket, owed to me, I owe, balance; warns on a missing rate) plus per-account
 * native balances.
 *
 * Balances are summed from leg projections, which never open a whole entry, so an entry of a kind
 * the app cannot read would simply be left out of them. Home therefore also reads the ledger's
 * health, and a failure there shows the cannot-be-read state instead of totals that miss a row.
 *
 * A read that throws (a stored row that cannot be decoded) becomes a failed state instead of
 * crashing the app. The catch ends that attempt's reads; [retry] starts a fresh attempt, and so does
 * the next start of the screen (once the reads have stopped while it was hidden).
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    getAccountBalances: GetAccountBalances,
    getAccountBalancesInBase: GetAccountBalancesInBase,
    computeHomeSummary: ComputeHomeSummary,
    observeLedgerHealth: ObserveLedgerHealth,
    currencies: CurrencyRepository,
    settings: SettingsRepository,
    private val moneyFormatter: MoneyFormatter,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    private val errors: ErrorReporter,
) : ViewModel() {
    private val attempts = ReadAttempts()

    val state: StateFlow<HomeUiState> = attempts.reading(loading = { HomeUiState() }) {
        flow {
            // Sharing belongs to this read attempt, so a retry or failed read cancels its producer.
            coroutineScope {
                val balances = getAccountBalances().shareIn(this, SharingStarted.Lazily, replay = 1)
                // The summary emits a live sealed result: a missing rate is a value, not a thrown error, so
                // this flow stays alive and re-resolves the moment the rate is set. The ledger-health
                // read emits while every entry can be read and fails the attempt the moment one cannot.
                emitAll(combine(getAccountBalancesInBase(balances), computeHomeSummary(balances),
                    currencies.observeAll(), settings.observe(), observeLedgerHealth()) {
                    accounts, summary, currencyList, currentSettings, _ ->
                    // Archived accounts drop out of the active list (and out of the summary); they stay
                    // reachable and unarchivable on the Manage Accounts screen.
                    HomeUiState(
                        summary = summary.toUi(currencyList),
                        accounts = accounts.filterNot { it.account.archived }.map { it.toRow(currencyList) },
                        baseCurrencyCode = currentSettings.baseCurrencyCode,
                        isLoading = false,
                        debtsBelowZero = when (summary) {
                            is HomeSummary.Amounts -> summary.debtsBelowZero
                            is HomeSummary.MissingRate -> summary.debtsBelowZero
                        },
                    )
                })
            }
        }
            // Balances are summed over the whole history and converted again on every write; that
            // belongs off the main thread, where the other screens already build theirs.
            .flowOn(defaultDispatcher).catch { error ->
            if (error is CancellationException) throw error
            errors.report("Home could not read the wallet", error)
            emit(HomeUiState(isLoading = false, loadFailed = true))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    /** Reads the wallet again after a failed read, showing loading until the new read answers. */
    fun retry() = attempts.retry()

    private fun HomeSummary.toUi(currencies: List<Currency>): HomeSummaryState = when (this) {
        is HomeSummary.Amounts -> HomeSummaryState.Amounts(moneyFormatter.display(pocket, currencies),
            moneyFormatter.display(owedToMe, currencies), moneyFormatter.display(iOwe, currencies),
            moneyFormatter.display(balance, currencies))
        is HomeSummary.MissingRate -> HomeSummaryState.MissingRate(currencyCode)
    }

    private fun AccountBalanceInBase.toRow(currencies: List<Currency>) = AccountRow(
        account, native, base, moneyFormatter.display(native, currencies),
        base?.let { moneyFormatter.display(it, currencies) }, credit,
        credit?.owed?.let { moneyFormatter.display(it, currencies) },
        credit?.available?.let { moneyFormatter.display(it, currencies) },
        credit?.takeIf { it.overLimit }?.let { status ->
            account.creditLimit?.let { moneyFormatter.display(status.owed - it, currencies) }
        },
    )

    private companion object { private const val STOP_TIMEOUT_MS = 5_000L }
}
