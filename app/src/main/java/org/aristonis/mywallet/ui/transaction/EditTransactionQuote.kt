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
    return RateQuoteInput(fx = fx, legs = rule?.quoteLegs(inputs, accounts, entry).orEmpty(),
        rateInputs = rateInputs, keptRates = if (entry != null) rule?.keptRates(entry).orEmpty() else emptyMap(),
        pair = rule?.quotePair(inputs, accounts, entry))
}
