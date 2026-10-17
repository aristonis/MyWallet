package org.aristonis.mywallet.data.db

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class DeductionMappersTest {
    @Test
    fun aDeductionSnapshotReadsItsSubCategoryFromItsLeg() {
        val line = DeductionLine(
            itemId = 9, position = 0, name = "Internet", mode = DeductionMode.FIXED,
            value = BigDecimal("20"), valueCurrencyCode = "USD", timing = DeductionTiming.NOW,
            categoryId = 7, subCategoryId = 8, amount = Money.of("20", "USD"),
        )
        val income = Transaction.Income(
            id = 1, accountId = 2, amount = Money.of("1000", "USD"), categoryId = 5,
            date = LocalDate.of(2026, 10, 17), deductions = listOf(line),
        )
        val parts = income.toParts()

        val restored = listOf(line.toEntity(income.id)).toSnapshots(parts)

        assertEquals(listOf(line), restored)
        assertEquals(income, parts.toRows().toTransaction(emptyMap()))
    }


    @Test
    fun anOwedLaterChildDeductionRestoresItsCompleteIncomeAndDebtTarget() {
        val line = DeductionLine(
            itemId = 9, position = 0, name = "Internet", mode = DeductionMode.FIXED,
            value = BigDecimal("20"), valueCurrencyCode = "USD", timing = DeductionTiming.LATER,
            categoryId = 7, subCategoryId = 8, amount = Money.of("20", "USD"), debtId = 31,
        )
        val income = Transaction.Income(
            id = 1, accountId = 2, amount = Money.of("1000", "USD"), categoryId = 5,
            date = LocalDate.of(2026, 10, 17), deductions = listOf(line),
        )
        val stored = income.toParts().toRows()

        val restored = stored.toTransaction(mapOf(31L to DebtSide.I_OWE)) as Transaction.Income

        assertEquals(income, restored)
        assertEquals(listOf(line), restored.deductions)
        val parts = restored.toParts()
        assertEquals(LegTarget.Category(7, 8), parts.legs.single { it.role == LegRole.DEDUCTION }.target)
        val debt = parts.legs.single { it.role == LegRole.DEDUCTION_DEBT }
        assertEquals(LegTarget.Debt(31, DebtSide.I_OWE), debt.target)
        assertEquals(Money.of("20", "USD"), debt.amount)
        assertEquals(Money.of("1000", "USD"), parts.legs.single { it.role == LegRole.ACCOUNT }.amount)
    }

    @Test
    fun invalidPersistedPercentageIsReportedAsAnUnreadableRow() {
        val row = DeductionItemEntity(
            id = 41,
            name = "Tax",
            nameKey = "tax",
            mode = "PERCENT",
            value = "120",
            valueCurrencyCode = null,
            timing = "NOW",
            expenseCategoryId = 3,
            enabled = true,
            sortOrder = 0,
        )

        val thrown = assertThrows(WalletException.UnreadableRow::class.java) { row.toDomain() }

        assertEquals(41L, thrown.id)
        assertEquals("deduction item", thrown.kind)
        assertTrue(thrown.cause is WalletException.DeductionOutOfRange)
    }

    @Test
    fun invalidPersistedFixedAmountIsReportedAsAnUnreadableRow() {
        val row = DeductionItemEntity(
            id = 42,
            name = "Insurance",
            nameKey = "insurance",
            mode = "FIXED",
            value = "0",
            valueCurrencyCode = "USD",
            timing = "NOW",
            expenseCategoryId = 3,
            enabled = true,
            sortOrder = 0,
        )

        val thrown = assertThrows(WalletException.UnreadableRow::class.java) { row.toDomain() }

        assertEquals(42L, thrown.id)
        assertEquals("deduction item", thrown.kind)
        assertTrue(thrown.cause is WalletException.DeductionValueNotPositive)
    }
}
