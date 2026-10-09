package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.service.CurrencyConverter
import org.aristonis.mywallet.domain.service.RateBook
import java.math.BigDecimal

/**
 * A new transfer, priced by the save from its source amount. The destination amount and rate the
 * form sent are placeholders and are replaced. The same currency copies 1:1 at rate 1; otherwise
 * the destination is converted through [book] (typed rates first, then saved) and the stored rate is
 * the pair rate.
 *
 * @throws WalletException.MissingRate when either currency of the pair has no rate.
 * @throws WalletException.AmountRoundsToZero when the destination rounds to nothing.
 */
internal fun Transaction.Transfer.pricedAsNew(destCurrency: String, book: RateBook): Transaction.Transfer {
    val from = sourceAmount.currencyCode
    if (from == destCurrency) {
        return copy(destAmount = Money.of(sourceAmount.amount, destCurrency), rateUsed = BigDecimal.ONE)
    }
    val converter = book.converter()
    val destAmount = converter.convert(sourceAmount, destCurrency)
    if (destAmount.isZero) throw WalletException.AmountRoundsToZero(destCurrency)
    return copy(destAmount = destAmount, rateUsed = converter.sourceToDestRate(from, destCurrency))
}

/**
 * An edited transfer is a historical fact: its destination is re-derived from the (possibly new)
 * source at the transfer's own stored rate, whatever destination the form sent, so editing a note or
 * date never moves money. The currency pair stays fixed, because the stored rate means nothing for
 * another pair; to use a different rate, delete and add the transfer again.
 *
 * @throws WalletException.TransferCurrencyPairChanged when the pair differs from [old]'s, or [old]
 *   was not a transfer.
 */
internal fun Transaction.Transfer.pricedAsEditOf(
    old: Transaction,
    destCurrency: String,
    fx: FxSnapshot,
): Transaction.Transfer {
    val storedPair = (old as? Transaction.Transfer)
        ?.let { it.sourceAmount.currencyCode to it.destAmount.currencyCode }
    if (storedPair != sourceAmount.currencyCode to destCurrency) {
        throw WalletException.TransferCurrencyPairChanged(old.id)
    }
    return copy(destAmount = convertAtStoredRate(sourceAmount, rateUsed, destCurrency, fx))
}

/** [source] at [rate], rounded to [destCurrency]'s decimals; a same-currency amount copies 1:1. */
private fun convertAtStoredRate(source: Money, rate: BigDecimal, destCurrency: String, fx: FxSnapshot): Money {
    if (source.currencyCode == destCurrency) return Money.of(source.amount, destCurrency)
    val decimals = fx.decimalPlaces[destCurrency] ?: throw WalletException.CurrencyNotFound(destCurrency)
    val converted = source.amount.multiply(rate).setScale(decimals, CurrencyConverter.ROUNDING)
    if (converted.signum() == 0) throw WalletException.AmountRoundsToZero(destCurrency)
    return Money.of(converted, destCurrency)
}
