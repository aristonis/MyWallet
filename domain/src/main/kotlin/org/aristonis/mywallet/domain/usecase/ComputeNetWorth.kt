package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.service.CurrencyConverter

/**
 * Live total net worth in the base currency (FR-13): sum every non-archived account balance,
 * converted to base. Fails loud if a needed rate is missing (FR-15) — the error propagates through
 * the Flow to the collector, which prompts the user to set the rate (never a silently wrong total).
 */
class ComputeNetWorth(
    private val getAccountBalances: GetAccountBalances,
    private val currencies: CurrencyRepository,
    private val rates: RateRepository,
    private val settings: SettingsRepository,
) {
    operator fun invoke(): Flow<Money> =
        combine(
            getAccountBalances(),
            currencies.observeAll(),
            rates.observeAll(),
            settings.observe(),
        ) { balances, currencyList, rateList, settingsValue ->
            val base = settingsValue.baseCurrencyCode
            val converter = CurrencyConverter(
                baseCurrencyCode = base,
                ratesToBase = rateList.associate { it.currencyCode to it.rateToBase },
                decimalPlaces = currencyList.associate { it.code to it.decimalPlaces },
            )
            balances
                .filterNot { it.account.archived }
                .fold(Money.zero(base)) { total, ab -> total + converter.convert(ab.balance, base) }
        }
}
