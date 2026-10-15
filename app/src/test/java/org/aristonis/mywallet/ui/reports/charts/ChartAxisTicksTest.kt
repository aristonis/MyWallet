package org.aristonis.mywallet.ui.reports.charts

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.assertEquals

class ChartAxisTicksTest {

    @Test
    fun `normal series returns low middle high values and first middle last dates`() {
        val points = listOf(
            point("2026-01-01", "30.00"),
            point("2026-02-01", "10.00"),
            point("2026-03-01", "40.00"),
        )

        val ticks = chartAxisTicks(points)

        assertEquals(decimals("10.00", "25.00", "40.00"), ticks.valueTicks)
        assertEquals(
            listOf(
                ChartDateTick(0, LocalDate.parse("2026-01-01")),
                ChartDateTick(1, LocalDate.parse("2026-02-01")),
                ChartDateTick(2, LocalDate.parse("2026-03-01")),
            ),
            ticks.dateTicks,
        )
    }

    @Test
    fun `flat series returns one value tick`() {
        val points = listOf(
            point("2026-01-01", "12.50"),
            point("2026-02-01", "12.50"),
            point("2026-03-01", "12.50"),
        )

        val ticks = chartAxisTicks(points)

        assertEquals(decimals("12.50"), ticks.valueTicks)
    }

    @Test
    fun `single point returns one value tick and one date tick`() {
        val point = point("2026-04-15", "99.00")

        val ticks = chartAxisTicks(listOf(point))

        assertEquals(decimals("99.00"), ticks.valueTicks)
        assertEquals(listOf(ChartDateTick(0, point.date)), ticks.dateTicks)
    }

    @Test
    fun `two points return two date ticks`() {
        val points = listOf(
            point("2026-05-01", "10.00"),
            point("2026-05-31", "20.00"),
        )

        val ticks = chartAxisTicks(points)

        assertEquals(
            listOf(ChartDateTick(0, points[0].date), ChartDateTick(1, points[1].date)),
            ticks.dateTicks,
        )
    }

    @Test
    fun `empty series returns no ticks`() {
        val ticks = chartAxisTicks(emptyList())

        assertEquals(emptyList<BigDecimal>(), ticks.valueTicks)
        assertEquals(emptyList<ChartDateTick>(), ticks.dateTicks)
    }

    @Test
    fun `negative series returns ordered value ticks`() {
        val points = listOf(
            point("2026-06-01", "-10.00"),
            point("2026-07-01", "-30.00"),
            point("2026-08-01", "-20.00"),
        )

        val ticks = chartAxisTicks(points)

        assertEquals(decimals("-30.00", "-20.00", "-10.00"), ticks.valueTicks)
    }

    @Test
    fun `middle value uses an exact decimal midpoint`() {
        val points = listOf(
            point("2026-09-01", "0.10"),
            point("2026-10-01", "0.20"),
        )

        val ticks = chartAxisTicks(points)

        assertEquals(BigDecimal("0.15"), ticks.valueTicks[1])
    }

    private fun point(date: String, balance: String) =
        NetWorthPointRow(LocalDate.parse(date), BigDecimal(balance), balance)

    private fun decimals(vararg values: String) = values.map(::BigDecimal)
}
