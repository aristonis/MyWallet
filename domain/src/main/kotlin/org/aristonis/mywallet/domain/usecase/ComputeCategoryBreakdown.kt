package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.aristonis.mywallet.domain.model.CategoryBreakdown
import org.aristonis.mywallet.domain.model.CategoryTotal
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.SubCategoryTotal
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.port.TransactionRepository
import org.aristonis.mywallet.domain.service.CurrencyConverter
import org.aristonis.mywallet.domain.service.EntryKind
import org.aristonis.mywallet.domain.service.ReportEntry
import org.aristonis.mywallet.domain.service.reportEntry

/**
 * The result of computing a category breakdown: either the income / expense totals, or a signal that
 * a rate is still missing. Sealed (rather than throwing) so the reactive flow stays live and resolves
 * once the user sets the missing rate. Mirrors [NetWorth] / [PeriodSummaryResult].
 */
sealed interface CategoryBreakdownResult {
    data class Resolved(val breakdown: CategoryBreakdown) : CategoryBreakdownResult
    data class MissingRate(val currencyCode: String) : CategoryBreakdownResult
}

/**
 * Live income and expense totals per category, and per sub-category within each, over an inclusive
 * [DateRange], in the base currency. Transfers are internal movement and are left out. Categories and
 * sub-categories come biggest total first; ties fall back to id ascending (the no-sub-category
 * remainder last) so the order is deterministic. If an in-range income or expense is in a currency
 * with no rate yet, emits [CategoryBreakdownResult.MissingRate] for the first such currency instead of
 * a silently wrong total.
 */
class ComputeCategoryBreakdown(
    private val transactions: TransactionRepository,
    private val fx: FxRepository,
) {
    operator fun invoke(range: DateRange): Flow<CategoryBreakdownResult> =
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
            // value, not an exception; that keeps the flow live across a later rate change. Every
            // counted entry is in the breakdown, so any of their currencies gates it.
            val missing = entries.asSequence()
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
                // Convert each entry once, here; everything below only sums base-currency amounts.
                val inBase = entries.map { it.copy(amount = converter.convert(it.amount, base)) }
                CategoryBreakdownResult.Resolved(
                    CategoryBreakdown(
                        income = totalsOf(inBase.filter { it.kind == EntryKind.INCOME }, base),
                        expense = totalsOf(inBase.filter { it.kind == EntryKind.EXPENSE }, base),
                    ),
                )
            }
        }

    /**
     * Group [entries], whose amounts are already in [base], by category, then by sub-category. Each
     * sub-category total sums per-entry converted amounts, and the category total sums those
     * sub-category totals, so a parent always equals the exact sum of its children (no rounding drift
     * from re-converting).
     */
    private fun totalsOf(entries: List<ReportEntry>, base: String): List<CategoryTotal> =
        entries.groupBy { it.categoryId }
            .map { (categoryId, inCategory) ->
                val subCategories = inCategory.groupBy { it.subCategoryId }
                    .map { (subCategoryId, inSub) -> SubCategoryTotal(subCategoryId, inSub.sumIn(base)) }
                    .sortedWith(SUB_CATEGORY_ORDER)
                val total = subCategories.fold(Money.zero(base)) { acc, sub -> acc + sub.total }
                CategoryTotal(categoryId, total, subCategories)
            }
            .sortedWith(CATEGORY_ORDER)

    private fun List<ReportEntry>.sumIn(base: String): Money =
        fold(Money.zero(base)) { acc, entry -> acc + entry.amount }

    private companion object {
        // Every total here is in the base currency, so comparing Money never hits a currency mismatch.
        val CATEGORY_ORDER: Comparator<CategoryTotal> =
            compareByDescending<CategoryTotal> { it.total }.thenBy { it.categoryId }

        val SUB_CATEGORY_ORDER: Comparator<SubCategoryTotal> =
            compareByDescending<SubCategoryTotal> { it.total }.thenBy(nullsLast()) { it.subCategoryId }
    }
}
