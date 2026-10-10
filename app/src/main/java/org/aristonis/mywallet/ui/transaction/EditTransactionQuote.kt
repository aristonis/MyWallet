package org.aristonis.mywallet.ui.transaction

internal fun EditTransactionUiState.quoteInput(): RateQuoteInput {
    val entry = loaded
    val rule = entry?.let(EntryFormRules::forEntry)
    val inputs = formInputs()
    return RateQuoteInput(fx = fx, legs = rule?.quoteLegs(inputs, accounts, entry).orEmpty(),
        rateInputs = rateInputs, keptRates = if (entry != null) rule?.keptRates(entry).orEmpty() else emptyMap(),
        pair = rule?.quotePair(inputs, accounts, entry))
}
