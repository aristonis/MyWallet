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

    /** The complete share distributed among positive spending categories. */
    private const val WHOLE_PERCENT = 100

    /** Enough digits for any fraction that ends as a pixel offset. */
    private val FRACTION_CONTEXT = MathContext.DECIMAL64

    private val WHOLE_PERCENT_DECIMAL = BigDecimal.valueOf(WHOLE_PERCENT.toLong())

    private data class ShareRemainder(
        val index: Int,
        val wholePercent: Int,
        val remainder: BigDecimal,
    )

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
     * Each positive value's whole-percent share, distributed by largest remainder so the shares add
     * to 100. The base excludes refunds because one larger than its spending makes the net total
     * smaller than the positive parts, so shares of that net would add past 100%. Zero and negative
     * values have no spending share.
     */
    fun spendingShares(values: List<BigDecimal>): List<Int?> {
        val base = positiveBase(values)
        if (base.signum() == 0) return List(values.size) { null }

        val remainders = values.mapIndexedNotNull { index, value ->
            if (value.signum() <= 0) return@mapIndexedNotNull null
            val (wholePercent, remainder) = value
                .multiply(WHOLE_PERCENT_DECIMAL)
                .divideAndRemainder(base)
            ShareRemainder(index, wholePercent.intValueExact(), remainder)
        }
        val unallocated = WHOLE_PERCENT - remainders.sumOf { it.wholePercent }
        val roundedUpIndexes = remainders
            .sortedWith(compareByDescending<ShareRemainder> { it.remainder }.thenBy { it.index })
            .take(unallocated)
            .map { it.index }
            .toSet()
        val sharesByIndex = remainders.associateBy { it.index }

        return values.indices.map { index ->
            sharesByIndex[index]?.let { share ->
                share.wholePercent + if (index in roundedUpIndexes) 1 else 0
            }
        }
    }

    /**
     * Each positive value's fraction of positive spending. The base excludes refunds because one
     * larger than its spending makes the net total smaller than the positive parts, so shares of
     * that net would add past 100%. Zero and negative values get no bar.
     */
    fun shareLengths(values: List<BigDecimal>): List<Float> {
        val base = positiveBase(values)
        if (base.signum() == 0) return List(values.size) { 0f }
        return values.map { value -> if (value.signum() > 0) fraction(value, base) else 0f }
    }

    private fun positiveBase(values: List<BigDecimal>): BigDecimal =
        values.asSequence()
            .filter { it.signum() > 0 }
            .fold(BigDecimal.ZERO) { total, value -> total.add(value) }

    /** [part] / [whole] as a Float clamped to 0..1; [whole] is always positive here. */
    private fun fraction(part: BigDecimal, whole: BigDecimal): Float =
        part.divide(whole, FRACTION_CONTEXT).toFloat().coerceIn(0f, 1f)
}
