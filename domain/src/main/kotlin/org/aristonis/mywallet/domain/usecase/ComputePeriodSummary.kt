package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.PeriodSummary
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.port.TransactionRepository
import org.aristonis.mywallet.domain.service.CurrencyConverter
import org.aristonis.mywallet.domain.service.PeriodRanges
import java.time.LocalDate

/**
 * The result of computing a period summary: either the totals, or a signal that a rate is still
 * missing. A sealed result (rather than throwing, as [CurrencyConverter] would) keeps the reactive
 * flow alive — when the user later sets the missing rate the flow re-emits and the report resolves
 * from [MissingRate] to [Resolved]. Mirrors [NetWorth].
 */
sealed interface PeriodSummaryResult {
    data class Resolved(val summary: PeriodSummary) : PeriodSummaryResult
    data class MissingRate(val currencyCode: String) : PeriodSummaryResult
}

/**
 * Live income / expense / net over a period, in the base currency. Transfers are excluded
 * (internal movement, not earning/spending). Opening balances are not transactions, so also excluded.
 * If an in-period income/expense is in a currency with no rate yet, emits [PeriodSummaryResult.MissingRate]
 * for the first such currency instead of a silently wrong total.
 */
class ComputePeriodSummary(
    private val transactions: TransactionRepository,
    private val currencies: CurrencyRepository,
    private val rates: RateRepository,
    private val settings: SettingsRepository,
) {
    operator fun invoke(period: TrackingPeriod, reference: LocalDate): Flow<PeriodSummaryResult> =
        combine(
            transactions.observeAll(),
            currencies.observeAll(),
            rates.observeAll(),
            settings.observe(),
        ) { txs, currencyList, rateList, settingsValue ->
            val base = settingsValue.baseCurrencyCode
            val ratesToBase = rateList.associate { it.currencyCode to it.rateToBase }
            val range = PeriodRanges.of(period, reference)
            val inPeriod = txs.filter { it.date in range }

            // Detect a missing rate up front (before any conversion throws) so the result stays a
            // value, not an exception — that is what keeps this flow live across a later rate change.
            // Only currencies that actually appear as in-period earning/spending need a rate; transfers
            // are excluded from the summary, so their currencies don't gate it.
            val missing = inPeriod.asSequence()
                .mapNotNull { tx ->
                    when (tx) {
                        is Transaction.Income -> tx.amount.currencyCode
                        is Transaction.Expense -> tx.amount.currencyCode
                        is Transaction.Transfer -> null
                    }
                }
                .firstOrNull { code -> code != base && code !in ratesToBase }

            if (missing != null) {
                PeriodSummaryResult.MissingRate(missing)
            } else {
                val converter = CurrencyConverter(
                    baseCurrencyCode = base,
                    ratesToBase = ratesToBase,
                    decimalPlaces = currencyList.associate { it.code to it.decimalPlaces },
                )

                // Transfers are internal movement — never summed here (only Income/Expense are folded).
                val income = inPeriod.filterIsInstance<Transaction.Income>()
                    .fold(Money.zero(base)) { acc, tx -> acc + converter.convert(tx.amount, base) }
                val expense = inPeriod.filterIsInstance<Transaction.Expense>()
                    .fold(Money.zero(base)) { acc, tx -> acc + converter.convert(tx.amount, base) }
                PeriodSummaryResult.Resolved(PeriodSummary(income = income, expense = expense, net = income - expense))
            }
        }
}
