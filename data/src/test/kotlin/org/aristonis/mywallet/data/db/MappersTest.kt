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
import org.junit.Assert.assertNull
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
    fun creditLimit_roundTripsAsText() {
        val a = Account(
            id = 4, name = "Visa", typeKey = "credit_card", currencyCode = "USD",
            openingBalance = Money.of("-120.00", "USD"), creditLimit = Money.of("2000.50", "USD"),
        )

        val entity = a.toEntity()

        assertEquals("2000.50", entity.creditLimit)
        assertEquals(a, entity.toDomain())
        assertEquals(Money.of("2000.50", "USD"), entity.toDomain().creditLimit)
    }

    @Test
    fun noCreditLimit_writesNullColumn_andReadsBackNull() {
        val a = Account(id = 5, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.of("1", "USD"))

        val entity = a.toEntity()

        assertNull(entity.creditLimit)
        assertNull(entity.toDomain().creditLimit)
    }

    @Test
    fun entityWithStoredLimit_readsItInTheAccountCurrency() {
        val entity = AccountEntity(
            id = 6, name = "Visa", typeKey = "credit_card", currencyCode = "EUR",
            openingBalanceAmount = "0", creditLimit = "750",
        )

        assertEquals(Money.of("750", "EUR"), entity.toDomain().creditLimit)
    }

    @Test
    fun feeColumns_areWrittenNull_becauseNothingWritesThemYet() {
        val a = Account(
            id = 7, name = "Visa", typeKey = "credit_card", currencyCode = "USD",
            openingBalance = Money.zero("USD"), creditLimit = Money.of("100", "USD"),
        )

        val entity = a.toEntity()

        assertNull(entity.defaultFeeMode)
        assertNull(entity.defaultFeeValue)
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
