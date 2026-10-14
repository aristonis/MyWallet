package org.aristonis.mywallet.ui.reports.charts

import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Turns exact amounts into fractions of a chart's width or height. This is presentation only: the
 * amounts stay [BigDecimal] up to the division, and only the finished fraction becomes a Float,
 * because it ends as a pixel position. Every function is total: a single value, a flat series or a
 * series with nothing positive gives finite fractions in 0..1, never a division by zero or NaN.
 */
internal object ChartGeometry {

    /** Where a point sits when there is no spread to place it on: halfway up, or halfway across. */
    private const val MIDDLE = 0.5f

    /** Enough digits for any fraction that ends as a pixel offset. */
    private val FRACTION_CONTEXT = MathContext.DECIMAL64

    /**
     * The height of each value as a fraction, 0 at the lowest value and 1 at the highest. A series
     * with no spread (one value, or all equal) sits in the middle.
     */
    fun lineHeights(values: List<BigDecimal>): List<Float> {
        if (values.isEmpty()) return emptyList()
        val lowest = values.min()
        val spread = values.max() - lowest
        if (spread.signum() == 0) return List(values.size) { MIDDLE }
        return values.map { fraction(it - lowest, spread) }
    }

    /**
     * The horizontal position of each date, by its day offset from the first: a month-end series that
     * ends two weeks after its last month end keeps that last segment short. Dates are ascending; a
     * single date, or dates all on one day, sit in the middle.
     */
    fun lineXs(dates: List<LocalDate>): List<Float> {
        if (dates.isEmpty()) return emptyList()
        val span = ChronoUnit.DAYS.between(dates.first(), dates.last())
        if (span <= 0L) return List(dates.size) { MIDDLE }
        return dates.map { date -> (ChronoUnit.DAYS.between(dates.first(), date).toFloat() / span).coerceIn(0f, 1f) }
    }

    /**
     * The height of zero on the [lineHeights] scale, or null when the series does not cross it. Only a
     * series with values on both sides of zero gets a zero line; otherwise it would sit on an edge
     * and read as a floor.
     */
    fun zeroLine(values: List<BigDecimal>): Float? {
        if (values.isEmpty()) return null
        val lowest = values.min()
        val highest = values.max()
        if (lowest.signum() >= 0 || highest.signum() <= 0) return null
        return fraction(lowest.negate(), highest - lowest)
    }

    /**
     * Each bar's length as a fraction of the longest. Only a positive value is drawn, scaled to the
     * largest positive value. Zero and negative values (a refund that outweighs the spending) get 0:
     * a bar for them would claim a share of spending that is not there.
     */
    fun barLengths(values: List<BigDecimal>): List<Float> {
        val largest = values.filter { it.signum() > 0 }.maxOrNull() ?: return List(values.size) { 0f }
        return values.map { if (it.signum() > 0) fraction(it, largest) else 0f }
    }

    /** [part] / [whole] as a Float clamped to 0..1; [whole] is always positive here. */
    private fun fraction(part: BigDecimal, whole: BigDecimal): Float =
        part.divide(whole, FRACTION_CONTEXT).toFloat().coerceIn(0f, 1f)
}
