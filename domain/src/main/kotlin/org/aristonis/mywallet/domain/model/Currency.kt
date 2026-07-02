package org.aristonis.mywallet.domain.model

/**
 * A currency the user tracks. Base-ness is NOT stored here — it is derived from
 * [Settings.baseCurrencyCode] (single source of truth). [decimalPlaces] drives formatting
 * (USD 2, JPY 0, KWD 3).
 */
data class Currency(
    val code: String,
    val symbol: String,
    val decimalPlaces: Int,
) {
    init {
        require(code.isNotBlank()) { "currency code must not be blank" }
        require(decimalPlaces in 0..4) { "decimalPlaces out of range: $decimalPlaces" }
    }
}
