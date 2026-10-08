package org.aristonis.mywallet.domain.model

/** Theme choice (FR-24). */
enum class ThemePreference { SYSTEM, LIGHT, DARK }

/** App-wide settings. [baseCurrencyCode] is the single source of truth for the base currency (C-5). */
data class Settings(
    val baseCurrencyCode: String,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val schemaVersion: Int = 1,
    /**
     * Whether a rate typed on a transaction form is also kept as that currency's rate. On by
     * default: the rate a user just paid at is usually the rate they want everywhere next.
     */
    val saveTransactionRates: Boolean = true,
) {
    init { require(baseCurrencyCode.isNotBlank()) { "baseCurrencyCode must not be blank" } }
}
