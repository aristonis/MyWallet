package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.PeriodSummary
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.port.TransactionRepository
import org.aristonis.mywallet.domain.service.CurrencyConverter
import org.aristonis.mywallet.domain.service.EntryKind
import org.aristonis.mywallet.domain.service.reportEntry

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
 * Live income / expense / net over an inclusive [DateRange], in the base currency. Transfers are excluded
 * (internal movement, not earning/spending). Opening balances are not transactions, so also excluded.
 * If an in-period income/expense is in a currency with no rate yet, emits [PeriodSummaryResult.MissingRate]
 * for the first such currency instead of a silently wrong total.
 */
class ComputePeriodSummary(
    private val transactions: TransactionRepository,
    private val fx: FxRepository,
) {
    operator fun invoke(range: DateRange): Flow<PeriodSummaryResult> =
        combine(
            transactions.observeAll(),
            fx.observeFx(),
        ) { txs, snapshot ->
            val base = snapshot.baseCurrencyCode
            val ratesToBase = snapshot.ratesToBase
            // Every report decision (what counts, and on which side) comes from reportEntry(), so a new
            // transaction kind can't slip past the rate check or out of the totals here.
            val entries = txs.filter { it.date in range }.mapNotNull { it.reportEntry() }

            // Detect a missing rate up front (before any conversion throws) so the result stays a
            // value, not an exception — that is what keeps this flow live across a later rate change.
            // Only currencies of counted entries need a rate; what a report leaves out doesn't gate it.
            val missing = entries.asSequence()
                .map { it.amount.currencyCode }
                .firstOrNull { code -> code != base && code !in ratesToBase }

            if (missing != null) {
                PeriodSummaryResult.MissingRate(missing)
            } else {
                val converter = CurrencyConverter(
                    baseCurrencyCode = base,
                    ratesToBase = ratesToBase,
                    decimalPlaces = snapshot.decimalPlaces,
                )
                // Convert each entry on its own, then sum exactly in the base currency.
                fun totalOf(kind: EntryKind): Money = entries.filter { it.kind == kind }
                    .fold(Money.zero(base)) { acc, entry -> acc + converter.convert(entry.amount, base) }

                val income = totalOf(EntryKind.INCOME)
                val expense = totalOf(EntryKind.EXPENSE)
                PeriodSummaryResult.Resolved(PeriodSummary(income = income, expense = expense, net = income - expense))
            }
        }
}
