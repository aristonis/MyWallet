package org.aristonis.mywallet.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.AccountBalanceInBase
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.usecase.ComputeNetWorth
import org.aristonis.mywallet.domain.usecase.GetAccountBalancesInBase
import javax.inject.Inject

/** Net-worth hero state. Missing rate is fail-loud (FR-15) — a warning, never a wrong total. */
sealed interface NetWorthState {
    data object Loading : NetWorthState
    data class Amount(val total: Money) : NetWorthState
    data class MissingRate(val currencyCode: String) : NetWorthState
}

/** Home renders from this. Holds domain objects; display formatting is done later at render time. */
data class HomeUiState(
    val netWorth: NetWorthState = NetWorthState.Loading,
    val accounts: List<AccountBalanceInBase> = emptyList(),
    val baseCurrencyCode: String? = null,
)

/**
 * Home: total net worth (base currency, fail-loud on a missing rate) + per-account native balances.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    getAccountBalancesInBase: GetAccountBalancesInBase,
    computeNetWorth: ComputeNetWorth,
    settings: SettingsRepository,
) : ViewModel() {

    // ComputeNetWorth THROWS MissingRate through the flow (FR-15). catch turns it into a warning
    // state so the account list still renders — never a silently wrong total. (Note: catch also
    // terminates this flow, so net worth won't auto-recover when a rate is later added — acceptable
    // until rate-management UI exists; a reactive result type is the eventual fix.)
    private val netWorth: Flow<NetWorthState> =
        computeNetWorth()
            .map<Money, NetWorthState> { NetWorthState.Amount(it) }
            .catch { cause ->
                if (cause is WalletException.MissingRate) emit(NetWorthState.MissingRate(cause.code))
                else throw cause
            }

    val state: StateFlow<HomeUiState> =
        combine(
            getAccountBalancesInBase(),
            netWorth,
            settings.observe(),
        ) { accounts, netWorthState, currentSettings ->
            HomeUiState(
                netWorth = netWorthState,
                accounts = accounts,
                baseCurrencyCode = currentSettings.baseCurrencyCode,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
}
