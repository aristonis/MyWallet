package org.aristonis.mywallet.data.db

import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/**
 * Every mapper must round-trip: domain → entity → domain reproduces the original exactly. Entries are
 * stored as legs and have their own tests (EntryMapperRoundTripTest, EntryLegRowsTest).
 */
class MappersTest {

    @Test
    fun account_roundTrips() {
        val a = Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.of("100.50", "USD"), archived = true, sortOrder = 3)
        assertEquals(a, a.toEntity().toDomain())
    }

    @Test
    fun category_roundTrips() {
        val c = Category(id = 2, name = "Groceries", kind = CategoryKind.EXPENSE, parentId = 5)
        assertEquals(c, c.toEntity().toDomain())
    }

    @Test
    fun currency_roundTrips() {
        val c = Currency(code = "KWD", symbol = "KD", decimalPlaces = 3)
        assertEquals(c, c.toEntity().toDomain())
    }

    @Test
    fun rate_roundTrips() {
        val r = ExchangeRate("EUR", BigDecimal("1.10"))
        assertEquals(r, r.toEntity().toDomain())
    }

    @Test
    fun settings_roundTrips() {
        val s = Settings(baseCurrencyCode = "USD", theme = ThemePreference.DARK, schemaVersion = 1)
        assertEquals(s, s.toEntity().toDomain())
    }
}
