package org.aristonis.mywallet.domain.model

import java.time.LocalDate

/** The selectable tracking periods (FR-17). */
enum class TrackingPeriod { DAY, WEEK, MONTH, YEAR, ALL_TIME }

/** An inclusive date range. `date in range` works via the [contains] operator. */
data class DateRange(val start: LocalDate, val endInclusive: LocalDate) {
    operator fun contains(date: LocalDate): Boolean =
        !date.isBefore(start) && !date.isAfter(endInclusive)
}

/** Income / expense / net over a period, all in the base currency (FR-17). */
data class PeriodSummary(val income: Money, val expense: Money, val net: Money)

/** One category's total spend (base currency) within a period (FR-18 breakdown). */
data class CategoryTotal(val categoryId: Long, val total: Money)
