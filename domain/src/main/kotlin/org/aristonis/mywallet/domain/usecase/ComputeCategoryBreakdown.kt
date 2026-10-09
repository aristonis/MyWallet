package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.EntryRecipes
import org.aristonis.mywallet.domain.ledger.TrackingRule
import org.aristonis.mywallet.domain.model.CategoryBreakdown
import org.aristonis.mywallet.domain.model.CategoryTotal
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.SubCategoryTotal
import org.aristonis.mywallet.domain.port.CategoryLeg
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.service.CurrencyConverter
import java.math.RoundingMode

/**
 * The result of computing a category breakdown: either the income / expense totals, or a signal that
 * a rate is still missing. Sealed (rather than throwing) so the reactive flow stays live and resolves
 * once the missing rate is filled in. Mirrors [NetWorth].
 */
sealed interface CategoryBreakdownResult {
    data class Resolved(val breakdown: CategoryBreakdown) : CategoryBreakdownResult
    data class MissingRate(val currencyCode: String) : CategoryBreakdownResult
}

/**
 * Live income and expense totals per category, and per sub-category within each, over an inclusive
 * [DateRange], in the base currency. The period's income / expense / net come with it as
 * [CategoryBreakdown.summary], so a report needs this one read per window.
 *
 * Tracking reads category legs, not entries. Each leg counts on the side its entry kind's recipe
 * gives for the leg's role ([EntryRecipes.trackingRule]), as 1.1.1 counted an entry by its type:
 * never by its category's kind. So an old income filed under an expense category stays income, and
 * a leg on a category deleted long ago still counts, grouped under that category id. A refund is a
 * negative leg that takes away from its expense category. Account and debt legs never reach here,
 * so moving money between accounts is never counted. A new kind counts once its recipe is
 * registered with its rules; nothing here changes.
 *
 * Each leg is converted at the rate stored on it, the rate it was recorded with, so a later change
 * of today's rate never moves a past month. A leg already in the base currency is kept as written;
 * any other leg is multiplied by its stored rate and rounded HALF_UP to the base's decimals, once,
 * and every total above it is an exact sum of those legs. Before that rounding the product is cut to
 * [PRODUCT_SCALE] decimals, HALF_EVEN: a base change divides stored rates, and switching away and
 * back leaves a residue far past that point (1.4999...97 instead of 1.5). Cut first, the residue
 * goes and a half-way leg rounds as it did before the round trip; any product that does not reach
 * that many decimals is unchanged.
 *
 * Fails with [WalletException.UnreadableRow] when a leg's entry kind has no recipe, or its role
 * has no rule there that counts. Emits
 * [CategoryBreakdownResult.MissingRate] when a leg in another currency than the base has no stored
 * rate, naming the currency of the newest such leg (date, then entry id, both descending), even if
 * a rate is saved today: guessing it from today's rate would quietly put a wrong amount in the past.
 */
class ComputeCategoryBreakdown(
    private val ledger: LedgerQueries,
    private val fx: FxRepository,
) {
    operator fun invoke(range: DateRange): Flow<CategoryBreakdownResult> =
        combine(ledger.observeCategoryLegs(range), fx.observeFx(), ::breakdownOf)

    private fun breakdownOf(legs: List<CategoryLeg>, snapshot: FxSnapshot): CategoryBreakdownResult {
        val base = snapshot.baseCurrencyCode
        // Every leg must be placeable before anything is reported, so an unreadable row is never
        // hidden behind a missing rate or left out of a total.
        val sides = legs.map { it.side() }

        val missing = legs.asSequence()
            .filter { it.amount.currencyCode != base && it.rateToBase == null }
            .maxWithOrNull(BY_RECENCY)
        if (missing != null) return CategoryBreakdownResult.MissingRate(missing.amount.currencyCode)

        val decimals = snapshot.decimalPlaces[base] ?: CurrencyConverter.DEFAULT_DECIMALS
        val counted = legs.mapIndexed { index, leg ->
            CountedLeg(sides[index], leg.categoryId, leg.subCategoryId, leg.inBase(base, decimals))
        }
        return CategoryBreakdownResult.Resolved(
            CategoryBreakdown(
                income = totalsOf(counted.filter { it.side == Side.INCOME }, base),
                expense = totalsOf(counted.filter { it.side == Side.EXPENSE }, base),
                baseCurrencyCode = base,
            ),
        )
    }

    /**
     * The side this leg counts on, from its entry kind's rule for its role. A refund's rule keeps it
     * on the expense side; its negative amount does the reducing. No `else`: a new rule fails to
     * compile here instead of dropping out of every total.
     */
    private fun CategoryLeg.side(): Side = when (EntryRecipes.trackingRule(entryKind, role)) {
        TrackingRule.INCOME -> Side.INCOME
        TrackingRule.EXPENSE, TrackingRule.REDUCES_EXPENSE -> Side.EXPENSE
        TrackingRule.NOT_COUNTED, null -> throw WalletException.UnreadableRow(entryId, entryKind)
    }

    /**
     * This leg in [base]. Only called once no leg outside the base lacks a rate, so [rateToBase] is
     * set wherever it is read.
     */
    private fun CategoryLeg.inBase(base: String, decimals: Int): Money {
        if (amount.currencyCode == base) return amount
        val rate = checkNotNull(rateToBase) { "leg of entry $entryId reached conversion with no rate" }
        val exact = amount.amount.multiply(rate)
        // Padding a short product out to the guard scale changes nothing but turns it into a big
        // integer, which is slow; only a product longer than the guard needs trimming.
        val product = if (exact.scale() > PRODUCT_SCALE) exact.setScale(PRODUCT_SCALE, RoundingMode.HALF_EVEN) else exact
        return Money.of(product.setScale(decimals, CurrencyConverter.ROUNDING), base)
    }

    /**
     * Group [legs], whose amounts are already in [base], by category, then by sub-category. Each
     * sub-category total sums per-leg converted amounts, and the category total sums those
     * sub-category totals, so a parent always equals the exact sum of its children (no rounding drift
     * from re-converting).
     */
    private fun totalsOf(legs: List<CountedLeg>, base: String): List<CategoryTotal> =
        legs.groupBy { it.categoryId }
            .map { (categoryId, inCategory) ->
                val subCategories = inCategory.groupBy { it.subCategoryId }
                    .map { (subCategoryId, inSub) -> SubCategoryTotal(subCategoryId, inSub.sumIn(base)) }
                    .sortedWith(SUB_CATEGORY_ORDER)
                val total = subCategories.fold(Money.zero(base)) { acc, sub -> acc + sub.total }
                CategoryTotal(categoryId, total, subCategories)
            }
            .sortedWith(CATEGORY_ORDER)

    private fun List<CountedLeg>.sumIn(base: String): Money =
        fold(Money.zero(base)) { acc, leg -> acc + leg.amount }

    /** Which half of the breakdown a leg lands in. */
    private enum class Side { INCOME, EXPENSE }

    /** A category leg placed on its side, with its amount already in the base currency. */
    private data class CountedLeg(
        val side: Side,
        val categoryId: Long,
        val subCategoryId: Long?,
        val amount: Money,
    )

    private companion object {
        /**
         * Decimals a leg's amount times its rate keeps before it is rounded to the base's decimals.
         * Far past any currency's decimals and any rate a user types, and far short of the 34
         * significant digits a base change divides with.
         */
        const val PRODUCT_SCALE = 20

        /**
         * Orders legs so the newest entry is the greatest, whatever order the ledger lists them in.
         * Legs of one entry tie; every category leg of an entry is in that entry's currency in every
         * kind today, so whichever of them wins names the same currency.
         */
        val BY_RECENCY: Comparator<CategoryLeg> =
            compareBy<CategoryLeg> { it.date }.thenBy { it.entryId }

        // Every total here is in the base currency, so comparing Money never hits a currency mismatch.
        val CATEGORY_ORDER: Comparator<CategoryTotal> =
            compareByDescending<CategoryTotal> { it.total }.thenBy { it.categoryId }

        val SUB_CATEGORY_ORDER: Comparator<SubCategoryTotal> =
            compareByDescending<SubCategoryTotal> { it.total }.thenBy(nullsLast()) { it.subCategoryId }
    }
}
