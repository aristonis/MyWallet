package org.aristonis.mywallet.ui.transaction

/**
 * What the rate previews are priced from, built the way the add form builds it. A transfer quotes
 * nothing here: its pair is locked and it is re-derived at its own stored rate, so a rate field would
 * change nothing. The entry's own rate is shown for the currency it was recorded in.
 */
internal fun EditTransactionUiState.quoteInput(): RateQuoteInput {
    val entry = loaded
    val rule = entry?.let(EntryFormRules::forEntry)
    val inputs = formInputs()
    val legs = rule?.quoteLegs(inputs, accounts, entry).orEmpty()
    // The original main rate applies only while its leg keeps that currency. A foreign fixed
    // deduction using the old currency after a move must read today's rate, as the save does.
    val kept = if (entry == null) emptyMap() else rule?.keptRates(entry).orEmpty()
        .filterKeys { code -> legs.any { it.currencyCode == code } }
    return RateQuoteInput(fx = fx, legs = legs, rateInputs = rateInputs, keptRates = kept,
        pair = rule?.quotePair(inputs, accounts, entry))
}
