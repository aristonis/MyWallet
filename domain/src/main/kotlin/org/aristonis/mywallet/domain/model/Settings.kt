package org.aristonis.mywallet.domain.model

/** Theme choice (FR-24). */
enum class ThemePreference { SYSTEM, LIGHT, DARK }

/** App-wide settings. [baseCurrencyCode] is the single source of truth for the base currency (C-5). */
data class Settings(
    val baseCurrencyCode: String,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val schemaVersion: Int = 1,
) {
    init { require(baseCurrencyCode.isNotBlank()) { "baseCurrencyCode must not be blank" } }
}
