package org.aristonis.mywallet.data.format

import org.aristonis.mywallet.domain.model.Money
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale
import org.aristonis.mywallet.domain.service.CurrencyConverter

/**
 * Parses a typed amount with the current locale and exact [BigDecimal] values. Ordinary locale
 * grouping is rejected; pasted Arabic grouping is accepted only in groups of three digits.
 * Parsing is strict: the whole trimmed string must be a number, so
 * "1,50abc" and "abc" are rejected rather than silently truncated.
 *
 * A fresh [DecimalFormat] is built per call: it is not thread-safe, so there is no shared mutable state
 * to synchronise (mirrors MoneyFormatter). Every rejection is a [MoneyParseException] naming a
 * [MoneyParseError]; wording it is the UI's job, not this layer's.
 */
class MoneyParser(private val localeProvider: () -> Locale) {

    constructor(locale: Locale) : this({ locale })

    /** Required, strictly positive. Built in [currencyCode] so it never touches a float. */
    fun parseAmount(input: String, currencyCode: String): Money {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) throw MoneyParseException(MoneyParseError.AMOUNT_MISSING)
        val parsed = exactWithinScale(trimmed, MoneyParseError.AMOUNT_OUT_OF_RANGE)
        if (parsed.signum() <= 0) throw MoneyParseException(MoneyParseError.AMOUNT_NOT_POSITIVE)
        return Money.of(parsed, currencyCode)
    }

    /** Required, strictly positive. Returns the raw [BigDecimal] — a rate is not a currency amount. */
    fun parseRate(input: String): BigDecimal {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) throw MoneyParseException(MoneyParseError.RATE_MISSING)
        val parsed = exactWithinScale(trimmed, MoneyParseError.RATE_OUT_OF_RANGE)
        if (parsed.signum() <= 0) throw MoneyParseException(MoneyParseError.RATE_NOT_POSITIVE)
        return parsed
    }

    /** Blank means zero; any sign is allowed. Scale-guarded like the other flavors. */
    fun parseOpeningBalance(input: String, currencyCode: String): Money {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Money.zero(currencyCode)
        return Money.of(exactWithinScale(trimmed, MoneyParseError.AMOUNT_OUT_OF_RANGE), currencyCode)
    }

    /**
     * Renders [amount] as a plain (ungrouped) string using the locale's decimal separator, so it round-
     * trips: `parse(toInputString(x)) == x` in every locale. Use it to pre-fill an edit field — a raw
     * toPlainString() '.' would be read as a grouping separator in a comma-decimal locale and misparsed.
     */
    fun toInputString(amount: BigDecimal): String =
        amount.toPlainString().replace('.', DecimalFormatSymbols.getInstance(localeProvider()).decimalSeparator)

    /** The locale-aware exact parse plus the shared scale guard; [outOfRange] names the flavor. */
    private fun exactWithinScale(trimmed: String, outOfRange: MoneyParseError): BigDecimal {
        val parsed = exactParse(trimmed)
        if (parsed.scale() !in -MAX_AMOUNT_SCALE..MAX_AMOUNT_SCALE) throw MoneyParseException(outOfRange)
        return parsed
    }

    /**
     * Parse with the locale's separators but keep full precision (BigDecimal, never a float). Strict:
     * the parse must consume the whole string, so partial matches like "1,50abc" fail the same as "abc".
     */
    private fun exactParse(trimmed: String): BigDecimal {
        val format = (NumberFormat.getInstance(localeProvider()) as DecimalFormat).apply {
            isParseBigDecimal = true
            positivePrefix = ""
            negativePrefix = "-"
            negativeSuffix = ""
        }
        val normalized = normalizeInput(trimmed, format.decimalFormatSymbols)
        val position = ParsePosition(0)
        val parsed = format.parse(normalized, position)
        if (parsed !is BigDecimal || position.index != normalized.length) {
            throw MoneyParseException(MoneyParseError.NOT_A_NUMBER)
        }
        return parsed
    }

    private fun normalizeInput(input: String, symbols: DecimalFormatSymbols): String {
        val digits = buildString {
            input.forEach { character ->
                val digit = Character.digit(character, 10)
                append(when {
                    digit >= 0 -> '0' + digit
                    character == symbols.minusSign -> '-'
                    else -> character
                })
            }
        }.trimStart('\u200f', '\u061c')
        val signed = if (digits.startsWith('-')) {
            "-" + digits.drop(1).trimStart('\u200f', '\u061c')
        } else {
            digits
        }
        // Ordinary locale grouping stays forbidden on input; Arabic pasted groups have an explicit
        // three-digit grammar so a misplaced separator cannot silently change a monetary value.
        if ((symbols.groupingSeparator != '\u066c' && symbols.groupingSeparator in signed) ||
            ('\u066c' in signed && !ARABIC_GROUPED_NUMBER.matches(signed))
        ) {
            throw MoneyParseException(MoneyParseError.NOT_A_NUMBER)
        }
        return signed.replace("\u066c", "")
            .replace('\u066b', symbols.decimalSeparator)
            .let { if (symbols.groupingSeparator != '.') it.replace('.', symbols.decimalSeparator) else it }
    }

    private companion object {
        // A real amount sits far inside this scale; an extreme exponent is a paste/typo that would also
        // OOM toPlainString(), so it is rejected at the boundary rather than crash later.
        private const val MAX_AMOUNT_SCALE = CurrencyConverter.MAX_STORED_SCALE
        private val ARABIC_GROUPED_NUMBER = Regex("""-?[0-9]{1,3}(?:\u066c[0-9]{3})+(?:[.,\u066b][0-9]+)?(?:E-?[0-9]+)?""")
    }
}
