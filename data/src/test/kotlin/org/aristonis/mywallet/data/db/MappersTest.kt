package org.aristonis.mywallet.data.db

import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.ThemePreference
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Every mapper must round-trip: domain → entity → domain reproduces the original exactly. */
class MappersTest {

    private val today = LocalDate.of(2026, 7, 2)

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

    @Test
    fun income_roundTrips() {
        val t = Transaction.Income(id = 1, accountId = 2, amount = Money.of("50", "USD"), categoryId = 3, subCategoryId = 4, date = today, note = "salary")
        assertEquals(t, t.toEntity().toDomain())
    }

    @Test
    fun expense_roundTrips() {
        val t = Transaction.Expense(id = 1, accountId = 2, amount = Money.of("12.50", "USD"), categoryId = 3, date = today)
        assertEquals(t, t.toEntity().toDomain())
    }

    @Test
    fun transfer_roundTrips() {
        val t = Transaction.Transfer(
            id = 1, sourceAccountId = 2, destAccountId = 3,
            sourceAmount = Money.of("5", "USD"), destAmount = Money.of("4.60", "EUR"),
            rateUsed = BigDecimal("0.92"), date = today, note = "move",
        )
        assertEquals(t, t.toEntity().toDomain())
    }
}
