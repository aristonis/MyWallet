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

sealed interface HomeSummaryState {
    data object Loading : HomeSummaryState
    data class Amounts(val pocketDisplay: String, val owedToMeDisplay: String, val iOweDisplay: String,
        val balanceDisplay: String) : HomeSummaryState
    data class MissingRate(val currencyCode: String) : HomeSummaryState
}

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

data class HomeUiState(
    val summary: HomeSummaryState = HomeSummaryState.Loading,
    val accounts: List<AccountRow> = emptyList(),
    val baseCurrencyCode: String? = null,
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val debtsBelowZero: List<Long> = emptyList(),
)

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
                emitAll(combine(getAccountBalancesInBase(balances), computeHomeSummary(balances),
                    currencies.observeAll(), settings.observe(), observeLedgerHealth()) {
                    accounts, summary, currencyList, currentSettings, _ ->
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
        }.flowOn(defaultDispatcher).catch { error ->
            if (error is CancellationException) throw error
            errors.report("Home could not read the wallet", error)
            emit(HomeUiState(isLoading = false, loadFailed = true))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

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
