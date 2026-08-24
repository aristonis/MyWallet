package org.aristonis.mywallet.data.db

import org.aristonis.mywallet.domain.model.CategoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultDataTest {

    @Test
    fun currenciesIncludeCommonCodes() {
        val codes = DefaultData.currencies.map { it.code }
        assertTrue(codes.containsAll(listOf("USD", "EUR", "SAR")))
    }

    @Test
    fun currenciesCoverFullIsoCatalog() {
        // Full ISO 4217 set (from java.util.Currency), not a hand-picked few.
        assertTrue("expected the full ISO catalog", DefaultData.currencies.size > 100)
        assertTrue(DefaultData.currencies.map { it.code }.containsAll(listOf("CHF", "INR", "AUD", "CAD")))
    }

    @Test
    fun everyCurrencyIsValidIsoWithSaneDecimals() {
        val isoCode = Regex("^[A-Z]{3}$")
        DefaultData.currencies.forEach { c ->
            assertTrue("code ${c.code} is not a 3-letter ISO code", isoCode.matches(c.code))
            assertTrue("decimals ${c.decimalPlaces} for ${c.code} out of range", c.decimalPlaces in 0..4)
        }
        assertEquals("no duplicate codes", DefaultData.currencies.size, DefaultData.currencies.map { it.code }.toSet().size)
    }

    @Test
    fun pseudoCurrenciesAreExcluded() {
        // "XXX" (No currency) has fraction digits -1 → must be filtered out (non-goal: no non-ISO units).
        assertFalse(DefaultData.currencies.map { it.code }.contains("XXX"))
    }

    @Test
    fun withdrawnCurrenciesAreExcluded() {
        // getAvailableCurrencies() also returns ~150 defunct codes; keep only the current set.
        val codes = DefaultData.currencies.map { it.code }
        assertFalse("ADP (Andorran peseta) is withdrawn", codes.contains("ADP"))
        assertFalse("AFA (old Afghani) is withdrawn", codes.contains("AFA"))
        assertFalse("ALK (old Albanian lek) is withdrawn", codes.contains("ALK"))
        assertTrue("expected the current set (~150), not the full historical catalog", DefaultData.currencies.size < 220)
    }

    @Test
    fun currencyDecimalsAreCorrect() {
        assertEquals(0, DefaultData.currencies.first { it.code == "JPY" }.decimalPlaces)
        assertEquals(3, DefaultData.currencies.first { it.code == "KWD" }.decimalPlaces)
        assertEquals(2, DefaultData.currencies.first { it.code == "USD" }.decimalPlaces)
    }

    @Test
    fun categoriesCoverBothKinds() {
        val kinds = DefaultData.categories.map { it.kind }.toSet()
        assertEquals(setOf(CategoryKind.INCOME, CategoryKind.EXPENSE), kinds)
    }

    @Test
    fun seedCategoriesAreAllTopLevel() {
        // The seed writes these as top-level rows; a parented one here would be silently flattened.
        assertTrue(DefaultData.categories.none { it.isSubCategory })
    }

    @Test
    fun everyStarterSubCategoryNamesAParentThatIsSeeded() {
        // The seed resolves the parent by name, and an unmatched name aborts database creation.
        val parents = DefaultData.categories.map { it.name }.toSet()
        DefaultData.subCategories.forEach {
            assertTrue("no seeded parent named \"${it.parentName}\"", it.parentName in parents)
        }
    }

    @Test
    fun starterSubCategoryParentNamesResolveToExactlyOneCategory() {
        // The seed matches the parent on name alone, so a name shared by two top-level categories
        // would insert the child twice, once under each.
        DefaultData.subCategories.map { it.parentName }.toSet().forEach { parentName ->
            assertEquals(
                "\"$parentName\" must name exactly one top-level category",
                1,
                DefaultData.categories.count { it.name.equals(parentName, ignoreCase = true) },
            )
        }
    }

    @Test
    fun starterSubCategoryNamesAreUniqueWithinTheirParent() {
        // The categories table has a unique index on (kind, parentId, name); a duplicate here would
        // abort the seed and leave the app unable to open its own database.
        val pairs = DefaultData.subCategories.map { it.parentName.lowercase() to it.name.lowercase() }
        assertEquals("no duplicate sibling names", pairs.size, pairs.toSet().size)
    }

    @Test
    fun starterSubCategoriesHangUnderExpenseParents() {
        val expenseNames = DefaultData.categories
            .filter { it.kind == CategoryKind.EXPENSE }
            .map { it.name }
            .toSet()
        DefaultData.subCategories.forEach {
            assertTrue("\"${it.parentName}\" is not an expense category", it.parentName in expenseNames)
        }
    }
}
