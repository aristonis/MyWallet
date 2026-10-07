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

/** Income and expense category totals within a range, each list biggest total first. */
data class CategoryBreakdown(val income: List<CategoryTotal>, val expense: List<CategoryTotal>)
