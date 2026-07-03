package org.aristonis.mywallet.data.db

import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import java.util.Locale
import java.util.Currency as PlatformCurrency

/**
 * Seed data inserted when the database is first created (via the Room `onCreate` callback). Base
 * currency + first account are set by onboarding, not seeded.
 */
object DefaultData {
    /**
     * The current ISO 4217 currency set, read from the platform ([PlatformCurrency]) rather than
     * fetched from the network — the data already ships in the OS (ICU), so this stays **offline**
     * (NFR-1) with zero maintenance.
     *
     * Two filters clean the raw catalog:
     * - `getAvailableCurrencies()` also returns ~150 **withdrawn** codes (ADP, AFA, …). We keep only
     *   currencies **in active use by some current locale** — a pure-JDK proxy for "current" that
     *   needs no network and stays unit-testable off-device. (Authoritative date-based filtering
     *   would need `android.icu` at seed time; deferred — see backlog.)
     * - Pseudo/non-currency codes (XXX, gold XAU, …) report `defaultFractionDigits == -1`; the
     *   `0..4` filter drops them (non-goal: no non-ISO units) and satisfies [Currency]'s invariant.
     *
     * Symbols come from the default locale (falls back to the code, e.g. "AED").
     */
    val currencies: List<Currency> = run {
        val inUse: Set<String> = Locale.getAvailableLocales()
            .filter { it.country.isNotEmpty() }
            .mapNotNull { locale -> runCatching { PlatformCurrency.getInstance(locale) }.getOrNull()?.currencyCode }
            .toSet()
        PlatformCurrency.getAvailableCurrencies()
            .filter { it.currencyCode in inUse && it.defaultFractionDigits in 0..4 }
            .map { Currency(code = it.currencyCode, symbol = it.symbol, decimalPlaces = it.defaultFractionDigits) }
            .sortedBy { it.code }
    }

    val categories: List<Category> = listOf(
        Category(name = "Salary", kind = CategoryKind.INCOME),
        Category(name = "Gifts", kind = CategoryKind.INCOME),
        Category(name = "Other Income", kind = CategoryKind.INCOME),
        Category(name = "Food", kind = CategoryKind.EXPENSE),
        Category(name = "Transport", kind = CategoryKind.EXPENSE),
        Category(name = "Bills", kind = CategoryKind.EXPENSE),
        Category(name = "Shopping", kind = CategoryKind.EXPENSE),
        Category(name = "Health", kind = CategoryKind.EXPENSE),
        Category(name = "Entertainment", kind = CategoryKind.EXPENSE),
        Category(name = "Other", kind = CategoryKind.EXPENSE),
    )
}
