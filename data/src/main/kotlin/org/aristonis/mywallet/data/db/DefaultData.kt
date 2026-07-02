package org.aristonis.mywallet.data.db

import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency

/**
 * Seed data inserted when the database is first created. The actual insert is wired in SG-7 via a
 * Room `onCreate` callback (which needs an Android Context). Base currency + first account are set
 * by onboarding, not seeded.
 */
object DefaultData {
    val currencies: List<Currency> = listOf(
        Currency("USD", "$", 2),
        Currency("EUR", "€", 2),
        Currency("GBP", "£", 2),
        Currency("JPY", "¥", 0),
        Currency("SAR", "SAR", 2),
        Currency("AED", "AED", 2),
        Currency("KWD", "KD", 3),
    )

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
