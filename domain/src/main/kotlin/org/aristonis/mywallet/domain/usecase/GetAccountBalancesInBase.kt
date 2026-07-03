package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.AccountBalanceInBase
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.service.CurrencyConverter

/**
 * Live per-account balances with each one also converted to the base currency (an account shows its
 * own currency AND the base). A missing rate makes only THAT account's base `null` (shown as "needs
 * a rate") — the rest of the list still resolves; total net worth is where a missing rate is fully
 * fail-loud. Re-runs whenever accounts, transactions, currencies, rates, or the base currency change.
 */
class GetAccountBalancesInBase(
    private val getAccountBalances: GetAccountBalances,
    private val currencies: CurrencyRepository,
    private val rates: RateRepository,
    private val settings: SettingsRepository,
) {
    operator fun invoke(): Flow<List<AccountBalanceInBase>> =
        combine(
            getAccountBalances(),
            currencies.observeAll(),
            rates.observeAll(),
            settings.observe(),
        ) { balances, currencyList, rateList, currentSettings ->
            val base = currentSettings.baseCurrencyCode
            val converter = CurrencyConverter(
                baseCurrencyCode = base,
                ratesToBase = rateList.associate { it.currencyCode to it.rateToBase },
                decimalPlaces = currencyList.associate { it.code to it.decimalPlaces },
            )
            balances.map { accountBalance ->
                // Only a missing rate nulls the base — never swallow other failures.
                val baseAmount = try {
                    converter.convert(accountBalance.balance, base)
                } catch (missing: WalletException.MissingRate) {
                    null
                }
                AccountBalanceInBase(accountBalance.account, accountBalance.balance, baseAmount)
            }
        }
}
