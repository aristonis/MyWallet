package org.aristonis.mywallet.domain.ledger

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.CategoryLeg
import org.aristonis.mywallet.domain.service.CurrencyConverter
import java.math.RoundingMode

/** Places a category leg by its recipe, preserving the stored sign for refunds. */
fun CategoryLeg.trackingSide(): CategoryKind = when (EntryRecipes.trackingRule(entryKind, role)) {
    TrackingRule.INCOME -> CategoryKind.INCOME
    TrackingRule.EXPENSE, TrackingRule.REDUCES_EXPENSE -> CategoryKind.EXPENSE
    TrackingRule.NOT_COUNTED, null -> throw WalletException.UnreadableRow(entryId, entryKind)
}

/**
 * Tracking's conversion boundary, shared by totals and export. Same-base values stay as typed;
 * foreign products remove rebase residue at 20 decimals HALF_EVEN, then round once HALF_UP.
 */
fun CategoryLeg.trackingAmount(base: String, decimals: Int): Money {
    if (amount.currencyCode == base) return amount
    val rate = rateToBase ?: throw WalletException.MissingRate(amount.currencyCode)
    val exact = amount.amount.multiply(rate)
    val product = if (exact.scale() > PRODUCT_SCALE) exact.setScale(PRODUCT_SCALE, RoundingMode.HALF_EVEN) else exact
    return Money.of(product.setScale(decimals, CurrencyConverter.ROUNDING), base)
}

private const val PRODUCT_SCALE = 20
