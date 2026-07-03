package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.service.CurrencyConverter

/**
 * The result of computing net worth: either the total, or a signal that a rate is still missing.
 * A sealed result (rather than throwing) keeps the reactive flow alive — when the user later sets
 * the missing rate, the flow re-emits and net worth resolves from [MissingRate] to an [Amount].
 */
sealed interface NetWorth {
    data class Amount(val total: Money) : NetWorth
    data class MissingRate(val currencyCode: String) : NetWorth
}

/**
 * Live total net worth in the base currency: sum every non-archived account balance, converted to
 * base. Never reports a silently wrong total — if any account's currency has no rate yet, it emits
 * [NetWorth.MissingRate] for the first such currency so the user can be prompted to set it.
 */
class ComputeNetWorth(
    private val getAccountBalances: GetAccountBalances,
    private val currencies: CurrencyRepository,
    private val rates: RateRepository,
    private val settings: SettingsRepository,
) {
    operator fun invoke(): Flow<NetWorth> =
        combine(
            getAccountBalances(),
            currencies.observeAll(),
            rates.observeAll(),
            settings.observe(),
        ) { balances, currencyList, rateList, settingsValue ->
            val base = settingsValue.baseCurrencyCode
            val ratesToBase = rateList.associate { it.currencyCode to it.rateToBase }
            val active = balances.filterNot { it.account.archived }

            // Detect a missing rate up front (before any conversion throws) so the result stays a
            // value, not an exception — that is what keeps this flow live across a later rate change.
            val missing = active
                .map { it.balance.currencyCode }
                .firstOrNull { code -> code != base && code !in ratesToBase }

            if (missing != null) {
                NetWorth.MissingRate(missing)
            } else {
                val converter = CurrencyConverter(
                    baseCurrencyCode = base,
                    ratesToBase = ratesToBase,
                    decimalPlaces = currencyList.associate { it.code to it.decimalPlaces },
                )
                val total = active.fold(Money.zero(base)) { sum, ab -> sum + converter.convert(ab.balance, base) }
                NetWorth.Amount(total)
            }
        }
}
