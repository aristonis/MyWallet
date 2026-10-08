package org.aristonis.mywallet.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.di.DefaultDispatcher
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.AccountBalanceInBase
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.usecase.ComputeNetWorth
import org.aristonis.mywallet.domain.usecase.GetAccountBalancesInBase
import org.aristonis.mywallet.domain.usecase.NetWorth
import org.aristonis.mywallet.ui.format.display
import javax.inject.Inject

/** Net-worth hero state. A missing rate becomes a warning, never a silently wrong total. */
sealed interface NetWorthState {
    data object Loading : NetWorthState
    data class Amount(val totalDisplay: String) : NetWorthState
    data class MissingRate(val currencyCode: String) : NetWorthState
}

/**
 * One account card. Keeps the native/base [Money] so the screen can still decide which line to show
 * (missing rate vs. a converted "≈" line vs. same currency); [nativeDisplay]/[baseDisplay] are the
 * amounts pre-formatted per currency + locale so no formatting happens in Compose.
 */
data class AccountRow(
    val account: Account,
    val native: Money,
    val base: Money?,
    val nativeDisplay: String,
    val baseDisplay: String?,
)

/** Home renders from this. Amounts arrive pre-formatted; the screen never touches a formatter. */
data class HomeUiState(
    val netWorth: NetWorthState = NetWorthState.Loading,
    val accounts: List<AccountRow> = emptyList(),
    val baseCurrencyCode: String? = null,
    /**
     * True until the first read arrives. Without it, the empty initial list is indistinguishable
     * from a wallet with no accounts, and a slow start flashes the "needs an account" prompt.
     */
    val isLoading: Boolean = true,
    /**
     * Stored data could not be read. The screen says so instead of showing an empty wallet, which
     * would invite the user to create a first account they already have.
     */
    val loadFailed: Boolean = false,
)

/**
 * Home: total net worth (base currency, warns on a missing rate) + per-account native balances.
 *
 * A read that throws (a stored row that cannot be decoded) becomes a failed state instead of
 * crashing the app. Home has no dates to change, so nothing re-reads straight away: the catch ends
 * the reads, and the next start of the screen (once the reads have stopped while it was hidden)
 * subscribes and reads again.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    getAccountBalancesInBase: GetAccountBalancesInBase,
    computeNetWorth: ComputeNetWorth,
    currencies: CurrencyRepository,
    settings: SettingsRepository,
    private val moneyFormatter: MoneyFormatter,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    private val errors: ErrorReporter,
) : ViewModel() {

    val state: StateFlow<HomeUiState> =
        combine(
            getAccountBalancesInBase(),
            // ComputeNetWorth emits a live sealed result: a missing rate is a value, not a thrown
            // error, so this flow stays alive and net worth re-resolves the moment the rate is set.
            computeNetWorth(),
            currencies.observeAll(),
            settings.observe(),
        ) { accounts, netWorthResult, currencyList, currentSettings ->
            HomeUiState(
                netWorth = netWorthState(netWorthResult, currencyList),
                // Archived accounts drop out of the active list (they're already out of net worth);
                // they stay reachable + unarchivable on the Manage Accounts screen.
                accounts = accounts.filterNot { it.account.archived }.map { it.toRow(currencyList) },
                baseCurrencyCode = currentSettings.baseCurrencyCode,
                isLoading = false,
            )
        }
            // Balances are summed over the whole history and converted again on every write; that
            // belongs off the main thread, where the other screens already build theirs.
            .flowOn(defaultDispatcher)
            .catch { error ->
                errors.report("Home could not read the wallet", error)
                emit(HomeUiState(isLoading = false, loadFailed = true))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    private fun netWorthState(result: NetWorth, currencies: List<Currency>): NetWorthState =
        when (result) {
            is NetWorth.Amount -> NetWorthState.Amount(moneyFormatter.display(result.total, currencies))
            is NetWorth.MissingRate -> NetWorthState.MissingRate(result.currencyCode)
        }

    private fun AccountBalanceInBase.toRow(currencies: List<Currency>): AccountRow =
        AccountRow(
            account = account,
            native = native,
            base = base,
            nativeDisplay = moneyFormatter.display(native, currencies),
            baseDisplay = base?.let { moneyFormatter.display(it, currencies) },
        )

    private companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
