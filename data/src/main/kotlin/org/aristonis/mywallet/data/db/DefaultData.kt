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
     * fetched from the network. The data already ships in the OS (ICU), so the app stays offline
     * and the list needs no maintenance.
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

    /** Top-level categories. Ids come from SQLite at insert time, so none are set here. */
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

    /**
     * A category every install carries for interest, tax, fees and written-off debts, and the
     * [role] that files legs under it, if any. Like every category the owner may rename or delete
     * one; a role whose category is gone falls back to the Uncategorized bucket of its kind.
     */
    data class PreMadeCategory(val name: String, val kind: CategoryKind, val role: String?)

    /**
     * Seeded once on a fresh install, after [categories]. The upgrade from an older database adds the
     * same rows from its own frozen copy of these literals, not from this list, so editing this
     * list changes new installs only.
     */
    val preMadeCategories: List<PreMadeCategory> = listOf(
        PreMadeCategory(name = "Interest", kind = CategoryKind.INCOME, role = "INTEREST_IN"),
        PreMadeCategory(name = "Interest", kind = CategoryKind.EXPENSE, role = "INTEREST_OUT"),
        PreMadeCategory(name = "Tax", kind = CategoryKind.EXPENSE, role = null),
        PreMadeCategory(name = "Fees", kind = CategoryKind.EXPENSE, role = "FEES"),
        PreMadeCategory(name = "Lost debt", kind = CategoryKind.EXPENSE, role = "LOST_DEBT"),
        PreMadeCategory(name = "Debt forgiven", kind = CategoryKind.INCOME, role = "DEBT_FORGIVEN"),
    )

    /**
     * A starter sub-category names its parent rather than pointing at an id, because the parent
     * only gets its id when the seed inserts it. The seed resolves the id and copies the parent's
     * kind along with it, so a child can never land under a parent of the other kind.
     */
    data class SubCategorySeed(val parentName: String, val name: String)

    /**
     * Enough of a second level to show what sub-categories are for without guessing at how anyone
     * budgets. The two-level cap means none of these may itself gain children.
     */
    val subCategories: List<SubCategorySeed> = listOf(
        SubCategorySeed(parentName = "Food", name = "Groceries"),
        SubCategorySeed(parentName = "Food", name = "Restaurants"),
        SubCategorySeed(parentName = "Transport", name = "Fuel"),
        SubCategorySeed(parentName = "Transport", name = "Public transport"),
        SubCategorySeed(parentName = "Bills", name = "Utilities"),
        SubCategorySeed(parentName = "Bills", name = "Rent"),
    )
}
