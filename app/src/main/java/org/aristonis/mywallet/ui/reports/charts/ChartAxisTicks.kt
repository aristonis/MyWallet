package org.aristonis.mywallet.ui.reports.charts

import java.math.BigDecimal
import java.time.LocalDate

internal data class ChartAxisTicks(
    val valueTicks: List<BigDecimal>,
    val dateTicks: List<ChartDateTick>,
)

internal data class ChartDateTick(val index: Int, val date: LocalDate)

internal fun chartAxisTicks(points: List<NetWorthPointRow>): ChartAxisTicks {
    if (points.isEmpty()) return ChartAxisTicks(emptyList(), emptyList())

    val lowest = points.minOf { it.balance }
    val highest = points.maxOf { it.balance }
    val valueTicks = if (lowest.compareTo(highest) == 0) {
        listOf(lowest)
    } else {
        listOf(lowest, lowest.add(highest).divide(MIDPOINT_DIVISOR), highest)
    }
    val dateTicks = listOf(FIRST_INDEX, points.size / MIDDLE_INDEX_DIVISOR, points.lastIndex)
        .distinct()
        .map { index -> ChartDateTick(index, points[index].date) }

    return ChartAxisTicks(valueTicks, dateTicks)
}

private const val FIRST_INDEX = 0
private const val MIDDLE_INDEX_DIVISOR = 2
private val MIDPOINT_DIVISOR = BigDecimal.valueOf(2)
