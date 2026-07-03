package org.aristonis.mywallet.data.format

import org.aristonis.mywallet.domain.model.Money
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale

/**
 * Parses a typed amount locale-aware AND exact. It reads the device locale's decimal and grouping
 * separators (a German user's "1,50" is 1.50, "1.000,50" is 1000.50) yet always yields a [BigDecimal] —
 * money never touches a float. Parsing is STRICT: the whole trimmed string must be a number, so
 * "1,50abc" and "abc" are rejected rather than silently truncated.
 *
 * A fresh [DecimalFormat] is built per call: it is not thread-safe, so there is no shared mutable state
 * to synchronise (mirrors MoneyFormatter). Each public method frames a user-facing message on failure.
 */
class MoneyParser(private val locale: Locale) {

    /** Required, strictly positive. Built in [currencyCode] so it never touches a float. */
    fun parseAmount(input: String, currencyCode: String): Money {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Enter an amount" }
        val parsed = exactWithinScale(trimmed, REALISTIC_AMOUNT)
        require(parsed.signum() > 0) { "Amount must be greater than 0" }
        return Money.of(parsed, currencyCode)
    }

    /** Required, strictly positive. Returns the raw [BigDecimal] — a rate is not a currency amount. */
    fun parseRate(input: String): BigDecimal {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Enter a rate" }
        val parsed = exactWithinScale(trimmed, "Enter a realistic rate")
        require(parsed.signum() > 0) { "Rate must be greater than 0" }
        return parsed
    }

    /** Blank means zero; any sign is allowed. Scale-guarded like the other flavors. */
    fun parseOpeningBalance(input: String, currencyCode: String): Money {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Money.zero(currencyCode)
        return Money.of(exactWithinScale(trimmed, REALISTIC_AMOUNT), currencyCode)
    }

    /**
     * Renders [amount] as a plain (ungrouped) string using the locale's decimal separator, so it round-
     * trips: `parse(toInputString(x)) == x` in every locale. Use it to pre-fill an edit field — a raw
     * toPlainString() '.' would be read as a grouping separator in a comma-decimal locale and misparsed.
     */
    fun toInputString(amount: BigDecimal): String =
        amount.toPlainString().replace('.', DecimalFormatSymbols.getInstance(locale).decimalSeparator)

    /** The locale-aware exact parse plus the shared scale guard; [scaleMessage] names the flavor. */
    private fun exactWithinScale(trimmed: String, scaleMessage: String): BigDecimal {
        val parsed = exactParse(trimmed)
        require(parsed.scale() in -MAX_AMOUNT_SCALE..MAX_AMOUNT_SCALE) { scaleMessage }
        return parsed
    }

    /**
     * Parse with the locale's separators but keep full precision (BigDecimal, never a float). Strict:
     * the parse must consume the whole string, so partial matches like "1,50abc" fail the same as "abc".
     */
    private fun exactParse(trimmed: String): BigDecimal {
        val format = (NumberFormat.getInstance(locale) as DecimalFormat).apply { isParseBigDecimal = true }
        // A money field takes a plain number, never a grouped one: reject any grouping separator so a
        // stray/misplaced one can't be silently misread (e.g. en-US "1,50" as 150). Display groups; input doesn't.
        require(format.decimalFormatSymbols.groupingSeparator !in trimmed) { "Enter a valid number" }
        val position = ParsePosition(0)
        val parsed = format.parse(trimmed, position)
        require(parsed is BigDecimal && position.index == trimmed.length) { "Enter a valid number" }
        return parsed
    }

    private companion object {
        // A real amount sits far inside this scale; an extreme exponent is a paste/typo that would also
        // OOM toPlainString(), so it is rejected at the boundary rather than crash later.
        private const val MAX_AMOUNT_SCALE = 30
        private const val REALISTIC_AMOUNT = "Enter a realistic amount"
    }
}
