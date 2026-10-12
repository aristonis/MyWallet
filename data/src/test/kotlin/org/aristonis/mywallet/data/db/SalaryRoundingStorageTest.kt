package org.aristonis.mywallet.data.db

import org.aristonis.mywallet.domain.ledger.EntryHeader
import org.aristonis.mywallet.domain.ledger.EntryParts
import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.model.DeductionInput
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.service.CurrencyConverter
import org.aristonis.mywallet.domain.service.DeductionCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class SalaryRoundingStorageTest {
    @Test fun paidNowPositivePercentageRoundedToZeroSurvivesStoredSnapshotRebuild() {
        assertRoundedZeroRoundTrip(DeductionTiming.NOW)
    }

    @Test fun owedLaterPositivePercentageRoundedToZeroSurvivesRebuildWithoutDebt() {
        assertRoundedZeroRoundTrip(DeductionTiming.LATER)
    }

    private fun assertRoundedZeroRoundTrip(timing: DeductionTiming) {
        val gross = Money.of("0.01", "USD")
        val input = DeductionInput(DeductionMode.PERCENT, BigDecimal.ONE, null, timing)
        val result = DeductionCalculator.calculate(
            gross, listOf(input), CurrencyConverter("USD", emptyMap(), mapOf("USD" to 2)),
        )
        val line = DeductionLine(
            itemId = 10, position = 0, name = "Tax", mode = input.mode, value = input.value,
            valueCurrencyCode = input.valueCurrencyCode, timing = input.timing, categoryId = 6,
            amount = result.lines.single().amount,
        )
        assertEquals(Money.zero("USD"), line.amount)
        val day = LocalDate.of(2026, 10, 12)
        val parts = EntryParts(
            EntryHeader(42, "INCOME", day, null),
            listOf(
                Leg(LegRole.ACCOUNT, 0, LegTarget.Account(1), result.accountReceives),
                Leg(LegRole.MAIN, 0, LegTarget.Category(5, null), gross, BigDecimal.ONE),
                Leg(LegRole.DEDUCTION, 0, LegTarget.Category(6, null), line.amount, BigDecimal.ONE),
            ),
            deductionLines = listOf(line),
        )
        val entities = listOf(line.toEntity(42))
        val restoredSnapshots = entities.toSnapshots(parts)
        assertEquals(listOf(line), restoredSnapshots)
        assertNull(restoredSnapshots.single().debtId)
        val stored = parts.toRows().copy(deductionLines = entities)
        val restored = stored.toTransaction(emptyMap()) as Transaction.Income
        assertEquals(gross, restored.amount)
        assertEquals(restoredSnapshots, restored.deductions)
        assertEquals(BigDecimal.ONE, restored.deductions.single().value)
        assertEquals(timing, restored.deductions.single().timing)
    }
}
