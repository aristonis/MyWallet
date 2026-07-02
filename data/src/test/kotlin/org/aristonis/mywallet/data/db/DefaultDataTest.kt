package org.aristonis.mywallet.data.db

import org.aristonis.mywallet.domain.model.CategoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultDataTest {

    @Test
    fun currenciesIncludeCommonCodes() {
        val codes = DefaultData.currencies.map { it.code }
        assertTrue(codes.containsAll(listOf("USD", "EUR", "SAR")))
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
}
