package org.aristonis.mywallet.domain.model

import java.time.LocalDate

/** The selectable tracking periods (FR-17). */
enum class TrackingPeriod { DAY, WEEK, MONTH, YEAR, ALL_TIME }

/**
 * An inclusive date range. `date in range` works via the [contains] operator. A reversed range is
 * rejected at construction: it would silently match nothing, so a report built on it would show
 * zeros instead of surfacing the caller's bug. All time is the [ALL_TIME] sentinel.
 */
data class DateRange(val start: LocalDate, val endInclusive: LocalDate) {
    init {
        require(!start.isAfter(endInclusive)) { "date range start $start is after its end $endInclusive" }
    }

    /**
     * Whether this is the [ALL_TIME] sentinel. Its bounds are not real dates a user picked, so a
     * caller must branch on this instead of formatting them or turning them into epoch millis
     * (`LocalDate.MAX` overflows a millis Long).
     */
    val isAllTime: Boolean get() = this == ALL_TIME

    operator fun contains(date: LocalDate): Boolean =
        !date.isBefore(start) && !date.isAfter(endInclusive)

    companion object {
        /** Every representable date. A sentinel: check [isAllTime] before displaying its bounds. */
        val ALL_TIME: DateRange = DateRange(LocalDate.MIN, LocalDate.MAX)
    }
}

/** Income / expense / net over a period, all in the base currency (FR-17). */
data class PeriodSummary(val income: Money, val expense: Money, val net: Money)

/**
 * One sub-category's total (base currency) within a range. A null [subCategoryId] is the remainder:
 * entries recorded without a sub-category, including those whose sub-category was later deleted.
 */
data class SubCategoryTotal(val subCategoryId: Long?, val total: Money)

/**
 * One category's total (base currency) within a range, split into its [subCategories].
 *
 * Invariant: [total] is the exact sum of the [subCategories] totals. Each entry is converted to the
 * base currency once and the parent is summed from the children, never re-converted from a raw
 * foreign sum, so per-entry rounding can't make a parent disagree with its parts.
 */
data class CategoryTotal(val categoryId: Long, val total: Money, val subCategories: List<SubCategoryTotal>)

/**
 * Income and expense category totals within a range, each list biggest total first, all in
 * [baseCurrencyCode]. The base currency is carried so an empty side still has a currency to be zero in.
 */
data class CategoryBreakdown(
    val income: List<CategoryTotal>,
    val expense: List<CategoryTotal>,
    val baseCurrencyCode: String,
) {
    /**
     * The period's income / expense / net. Derived from the category totals, which are already in the
     * base currency, rather than converted again from the raw entries: the headline numbers then always
     * equal the sum of the lists under them, with no second rounding to drift apart.
     */
    val summary: PeriodSummary
        get() {
            val incomeTotal = income.sumIn(baseCurrencyCode)
            val expenseTotal = expense.sumIn(baseCurrencyCode)
            return PeriodSummary(income = incomeTotal, expense = expenseTotal, net = incomeTotal - expenseTotal)
        }

    private fun List<CategoryTotal>.sumIn(currencyCode: String): Money =
        fold(Money.zero(currencyCode)) { acc, category -> acc + category.total }
}
