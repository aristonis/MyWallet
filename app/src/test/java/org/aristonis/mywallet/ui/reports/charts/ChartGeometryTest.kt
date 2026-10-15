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
    fun shareLengths_whenNothingIsPositive_areAllZero_withoutDividingByZero() {
        val lengths = ChartGeometry.shareLengths(values("-8.00", "0.00"))

        assertEquals(listOf(0f, 0f), lengths)
        assertFinite(lengths)
    }

    /** Positive spending shares use the positive subtotal and ignore refunds. */
    @Test
    fun `spending shares use positive values as the base`() {
        val shares = ChartGeometry.spendingShares(values("100", "50", "-30"))

        assertEquals(listOf(67, 33, null), shares)
    }

    /** Equal remainders are awarded in input order so rounding is deterministic. */
    @Test
    fun `spending shares break equal largest remainders by earlier index`() {
        val shares = ChartGeometry.spendingShares(values("1", "1", "1"))

        assertEquals(listOf(34, 33, 33), shares)
    }

    /** A lone positive category owns the whole positive spending base. */
    @Test
    fun `spending shares give a single positive value one hundred percent`() {
        val shares = ChartGeometry.spendingShares(values("42.50"))

        assertEquals(listOf(100), shares)
    }

    /** Refund-only input has no spending base and therefore no shares. */
    @Test
    fun `spending shares are null when every value is a refund`() {
        val shares = ChartGeometry.spendingShares(values("-10", "-25"))

        assertEquals(listOf(null, null), shares)
    }

    /** Empty input preserves its empty shape. */
    @Test
    fun `spending shares of empty input are empty`() {
        val shares = ChartGeometry.spendingShares(emptyList<BigDecimal>())

        assertEquals(emptyList<Int?>(), shares)
    }

    /** Zero values receive no share and do not affect the positive base. */
    @Test
    fun `spending shares are null for zero values`() {
        val shares = ChartGeometry.spendingShares(values("0", "25", "0"))

        assertEquals(listOf(null, 100, null), shares)
    }

    /** Share lengths use the positive subtotal while refunds draw no bar. */
    @Test
    fun `share lengths are fractions of positive spending`() {
        val lengths = ChartGeometry.shareLengths(values("100", "50", "-30"))

        assertEquals(0.6667f, lengths[0], 0.001f)
        assertEquals(0.3333f, lengths[1], 0.001f)
        assertEquals(0f, lengths[2], 0.001f)
        assertFinite(lengths)
    }
}
