package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.aristonis.mywallet.domain.model.CategoryTotal
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.port.TransactionRepository
import org.aristonis.mywallet.domain.service.CurrencyConverter
import org.aristonis.mywallet.domain.service.PeriodRanges
import java.time.LocalDate

/**
 * The result of computing a category breakdown: either the per-category totals, or a signal that a
 * rate is still missing. Sealed (rather than throwing) so the reactive flow stays live and resolves
 * once the user sets the missing rate. Mirrors [NetWorth] / [PeriodSummaryResult].
 */
sealed interface CategoryBreakdownResult {
    data class Resolved(val totals: List<CategoryTotal>) : CategoryBreakdownResult
    data class MissingRate(val currencyCode: String) : CategoryBreakdownResult
}

/**
 * Live per-category expense totals within a period, in the base currency. Same grouping
 * pattern extends to account / sub-category breakdowns when the UI needs them. If an in-period
 * expense is in a currency with no rate yet, emits [CategoryBreakdownResult.MissingRate] for the
 * first such currency instead of a silently wrong total.
 */
class ComputeCategoryBreakdown(
    private val transactions: TransactionRepository,
    private val fx: FxRepository,
) {
    operator fun invoke(period: TrackingPeriod, reference: LocalDate): Flow<CategoryBreakdownResult> =
        combine(
            transactions.observeAll(),
            fx.observeFx(),
        ) { txs, snapshot ->
            val base = snapshot.baseCurrencyCode
            val ratesToBase = snapshot.ratesToBase
            val range = PeriodRanges.of(period, reference)
            val inPeriodExpenses = txs.filter { it.date in range }.filterIsInstance<Transaction.Expense>()

            // Detect a missing rate up front (before any conversion throws) so the result stays a
            // value, not an exception — that keeps the flow live across a later rate change. Only
            // expense currencies gate the breakdown (income/transfers aren't in it).
            val missing = inPeriodExpenses.asSequence()
                .map { it.amount.currencyCode }
                .firstOrNull { code -> code != base && code !in ratesToBase }

            if (missing != null) {
                CategoryBreakdownResult.MissingRate(missing)
            } else {
                val converter = CurrencyConverter(
                    baseCurrencyCode = base,
                    ratesToBase = ratesToBase,
                    decimalPlaces = snapshot.decimalPlaces,
                )
                val totals = inPeriodExpenses
                    .groupBy { it.categoryId }
                    .map { (categoryId, expenses) ->
                        val total = expenses.fold(Money.zero(base)) { acc, e -> acc + converter.convert(e.amount, base) }
                        CategoryTotal(categoryId, total)
                    }
                CategoryBreakdownResult.Resolved(totals)
            }
        }
}
