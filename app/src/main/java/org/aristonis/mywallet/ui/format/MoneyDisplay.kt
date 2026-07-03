package org.aristonis.mywallet.ui.format

import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money

/** Two decimals: the fallback fraction digits when a code is missing from the seeded currency list. */
private const val FALLBACK_DECIMAL_PLACES = 2

/**
 * Format [money] for display, driven by the STORED [Currency.decimalPlaces] for its code (the single
 * source of truth — USD 2, JPY 0, KWD 3). If the code isn't in [currencies] (shouldn't happen — all
 * are seeded) it falls back to two decimals and the raw code so an amount still renders, never blanks.
 * Shared by the view-models so per-currency + locale formatting lives in exactly one place.
 */
fun MoneyFormatter.display(money: Money, currencies: List<Currency>): String {
    val currency = currencies.firstOrNull { it.code == money.currencyCode }
    return if (currency != null) format(money, currency)
    else format(money.amount, FALLBACK_DECIMAL_PLACES, money.currencyCode)
}
