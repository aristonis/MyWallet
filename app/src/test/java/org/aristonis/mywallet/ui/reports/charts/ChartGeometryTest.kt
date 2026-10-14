package org.aristonis.mywallet.ui.reports.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Chart geometry turns exact amounts into pixel fractions. It must never divide by zero or produce
 * NaN, whatever the series looks like, and a value that is not a positive spend never gets a bar.
 */
class ChartGeometryTest {

    private fun values(vararg amounts: String) = amounts.map(::BigDecimal)

    private fun assertFinite(fractions: List<Float>) =
        fractions.forEach { assertTrue("not a finite fraction: $it", it.isFinite() && it in 0f..1f) }

    @Test
    fun lineHeights_spanBottomToTop_fromLowestToHighest() {
        val heights = ChartGeometry.lineHeights(values("100.00", "150.00", "200.00"))

        assertEquals(listOf(0f, 0.5f, 1f), heights)
    }

    @Test
    fun lineHeights_ofASinglePoint_sitInTheMiddle_withoutDividingByZero() {
        val heights = ChartGeometry.lineHeights(values("2430.00"))

        assertEquals(listOf(0.5f), heights)
    }

    @Test
    fun lineHeights_ofAFlatSeries_sitInTheMiddle_withoutNaN() {
        val heights = ChartGeometry.lineHeights(values("10.00", "10.00", "10.00"))

        assertEquals(listOf(0.5f, 0.5f, 0.5f), heights)
    }

    @Test
    fun lineHeights_ofANegativeSeries_keepTheirOrder() {
        val heights = ChartGeometry.lineHeights(values("-300.00", "-100.00", "-200.00"))

        assertEquals(listOf(0f, 1f, 0.5f), heights)
        assertFinite(heights)
    }

    @Test
    fun lineHeights_ofNothing_isEmpty() {
        assertEquals(emptyList<Float>(), ChartGeometry.lineHeights(emptyList()))
    }

    @Test
    fun lineXs_placePointsByDay_soAShortLastSegmentStaysShort() {
        // A year-like series: month ends, then the selected end two weeks after the last month end.
        val dates = listOf(
            LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31),
            LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 14),
        )
        val span = 256f // 31 Jan to 14 Oct

        val xs = ChartGeometry.lineXs(dates)

        assertEquals(listOf(0f, 28f / span, 59f / span, 242f / span, 1f), xs)
    }

    @Test
    fun lineXs_centreASinglePoint_andHandleNothing() {
        assertEquals(listOf(0.5f), ChartGeometry.lineXs(listOf(LocalDate.of(2026, 10, 14))))
        assertEquals(emptyList<Float>(), ChartGeometry.lineXs(emptyList()))
    }

    @Test
    fun zeroLine_isDrawnOnlyWhenTheSeriesCrossesZero() {
        assertEquals(0.25f, ChartGeometry.zeroLine(values("-100", "300"))!!, 0f)
        assertNull(ChartGeometry.zeroLine(values("100", "300")))
        assertNull(ChartGeometry.zeroLine(values("-100", "-300")))
        assertNull(ChartGeometry.zeroLine(values("0", "0")))
    }

    @Test
    fun barLengths_scaleToTheLargestPositiveSpend() {
        val lengths = ChartGeometry.barLengths(values("200.00", "50.00", "100.00"))

        assertEquals(listOf(1f, 0.25f, 0.5f), lengths)
    }

    @Test
    fun barLengths_drawNothingForARefundOrZeroCategory() {
        // A negative net category is a refund effect, not a share: it never gets a positive bar.
        val lengths = ChartGeometry.barLengths(values("40.00", "-8.00", "0.00"))

        assertEquals(listOf(1f, 0f, 0f), lengths)
    }

    @Test
    fun barLengths_whenNothingIsPositive_areAllZero_withoutDividingByZero() {
        val lengths = ChartGeometry.barLengths(values("-8.00", "0.00"))

        assertEquals(listOf(0f, 0f), lengths)
        assertFinite(lengths)
    }
}
