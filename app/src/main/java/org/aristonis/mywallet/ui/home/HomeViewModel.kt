package org.aristonis.mywallet.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.aristonis.mywallet.domain.model.AccountBalanceInBase
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.usecase.ComputeNetWorth
import org.aristonis.mywallet.domain.usecase.GetAccountBalancesInBase
import org.aristonis.mywallet.domain.usecase.NetWorth
import javax.inject.Inject

/** Net-worth hero state. A missing rate becomes a warning, never a silently wrong total. */
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
 * Home: total net worth (base currency, warns on a missing rate) + per-account native balances.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    getAccountBalancesInBase: GetAccountBalancesInBase,
    computeNetWorth: ComputeNetWorth,
    settings: SettingsRepository,
) : ViewModel() {

    // ComputeNetWorth emits a live sealed result: a missing rate is a value, not a thrown error, so
    // this flow stays alive and net worth re-resolves the moment the user sets the rate.
    private val netWorth: Flow<NetWorthState> =
        computeNetWorth().map { result ->
            when (result) {
                is NetWorth.Amount -> NetWorthState.Amount(result.total)
                is NetWorth.MissingRate -> NetWorthState.MissingRate(result.currencyCode)
            }
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
