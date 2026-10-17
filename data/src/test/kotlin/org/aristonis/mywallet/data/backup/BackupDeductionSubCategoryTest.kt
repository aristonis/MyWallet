package org.aristonis.mywallet.data.backup

import org.aristonis.mywallet.data.db.AccountEntity
import org.aristonis.mywallet.data.db.CategoryEntity
import org.aristonis.mywallet.data.db.CurrencyEntity
import org.aristonis.mywallet.data.db.DeductionItemEntity
import org.aristonis.mywallet.data.db.SettingsEntity
import org.aristonis.mywallet.data.db.StoredEntry
import org.aristonis.mywallet.data.db.toRows
import org.aristonis.mywallet.data.db.toTransaction
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class BackupDeductionSubCategoryTest {
    @Test
    fun aChildDeductionSurvivesExportValidatedDecodeAndStoredEntryRead() {
        val income = Transaction.Income(
            id = 1, accountId = 2, amount = Money.of("1000", "USD"), categoryId = 5,
            date = LocalDate.of(2026, 10, 17), rateToBase = BigDecimal.ONE,
            deductions = listOf(DeductionLine(
                itemId = 9, position = 0, name = "Internet", mode = DeductionMode.FIXED,
                value = BigDecimal("20"), valueCurrencyCode = "USD", timing = DeductionTiming.NOW,
                categoryId = 7, subCategoryId = 8, amount = Money.of("20", "USD"),
            )),
        )
        val rows = income.toParts().toRows()
        val snapshot = TableSnapshot(
            settings = SettingsEntity(baseCurrencyCode = "USD", theme = "SYSTEM", schemaVersion = 3),
            accounts = listOf(AccountEntity(
                id = 2, name = "Bank", typeKey = "cash", currencyCode = "USD", openingBalanceAmount = "0",
            )),
            categories = listOf(
                CategoryEntity(id = 5, name = "Salary", kind = "INCOME"),
                CategoryEntity(id = 7, name = "Bills", kind = "EXPENSE"),
                CategoryEntity(id = 8, name = "Internet", kind = "EXPENSE", parentId = 7),
            ),
            currencies = listOf(CurrencyEntity("USD", "$", 2)), rates = emptyList(),
            entries = listOf(rows.entry), legs = rows.legs, debts = emptyList(),
            deductionItems = listOf(DeductionItemEntity(
                id = 9, name = "Internet", nameKey = "internet", mode = "FIXED", value = "20",
                valueCurrencyCode = "USD", timing = "NOW", expenseCategoryId = 8, enabled = true, sortOrder = 0,
            )),
            deductionAutoCategories = emptyList(), deductionLines = rows.deductionLines,
            categoryRoles = emptyList(), legacyRows = emptyList(),
        )

        val decoded = decodeValidated(BackupCodec.encode(buildBackup(snapshot)))
        val restored = StoredEntry(
            decoded.entries.single().toEntity(), decoded.legs.map { it.toEntity() },
            decoded.deductionLines.map { it.toEntity() },
        ).toTransaction(emptyMap()) as Transaction.Income

        assertEquals(income, restored)
        assertEquals(7L, restored.deductions.single().categoryId)
        assertEquals(8L, restored.deductions.single().subCategoryId)
        assertEquals(Money.of("20", "USD"), restored.deductions.single().amount)
        val parts = restored.toParts()
        assertEquals(LegTarget.Category(7, 8), parts.legs.single { it.role == LegRole.DEDUCTION }.target)
        assertEquals(Money.of("980", "USD"), parts.legs.single { it.role == LegRole.ACCOUNT }.amount)
    }
}
