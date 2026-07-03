package org.aristonis.mywallet.data.format

import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.service.CurrencyConverter
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale

/**
 * Renders amounts for display: locale-aware grouping and decimal separators, exactly the currency's
 * fraction digits, rounded with the app's single rounding mode ([CurrencyConverter.ROUNDING]), then
 * an ISO code suffix (e.g. "1,000.50 USD"). Pure JVM (no Android), so it unit-tests on the host.
 *
 * A fresh [DecimalFormat] is built per call: it is not thread-safe and the fraction digits vary by
 * currency, so there is no shared mutable state to synchronise.
 */
class MoneyFormatter(private val locale: Locale) {

    /** Format [amount] with [decimalPlaces] fraction digits, suffixed with the ISO [currencyCode]. */
    fun format(amount: BigDecimal, decimalPlaces: Int, currencyCode: String): String {
        val formatter = NumberFormat.getNumberInstance(locale) as DecimalFormat
        formatter.isGroupingUsed = true
        formatter.minimumFractionDigits = decimalPlaces
        formatter.maximumFractionDigits = decimalPlaces
        formatter.roundingMode = CurrencyConverter.ROUNDING
        return "${formatter.format(amount)} $currencyCode"
    }

    /** Convenience overload driven by the domain value objects. */
    fun format(money: Money, currency: Currency): String =
        format(money.amount, currency.decimalPlaces, currency.code)
}
