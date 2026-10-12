package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/**
 * A debt can be fed by a real income whose owed-later deduction created it. That income is kept when
 * the debt is deleted: its header, deduction lines and DEDUCTION_DEBT legs must come through unchanged.
 */
class DebtDeletionRealIncomeTest {

    private val debt: Debt = deletionDebt()

    private fun laterDeductionIncome() = Transaction.Income(
        id = 10,
        accountId = 1,
        amount = Money.of("1000", "USD"),
        categoryId = 5,
        date = deletionDay,
        deductions = listOf(
            DeductionLine(
                itemId = 20,
                position = 0,
                name = "Family",
                mode = DeductionMode.FIXED,
                value = BigDecimal("100"),
                valueCurrencyCode = "USD",
                timing = DeductionTiming.LATER,
                categoryId = 21,
                amount = Money.of("100", "USD"),
                debtId = debt.id,
            ),
        ),
    )

    private fun rig(income: Transaction.Income) = SaveEntryRig(
        initialEntries = listOf(deletionOpening(debt), deletionPayment(debt), income),
        initialDebts = listOf(debt),
        atomic = true,
    )

    @Test fun previewListsTheRetainedIncomeAndCountsItsOwedLaterDebtInTheRemainder() = runTest {
        val income = laterDeductionIncome()
        val rig = rig(income)
        val before = rig.entries.rows.value

        val preview = PreviewDebtDeletion(rig.runner, rig.debts, rig.deletionEntries(), rig.ledger)(debt.id)

        assertEquals(listOf(income.id), preview.retainedEntryIds)
        assertEquals(setOf(1L, 2L), preview.entries.map { it.id }.toSet())
        assertEquals(Money.of("100", "USD"), preview.remainingAfter)
        assertEquals(before, rig.entries.rows.value)
    }

    @Test fun deleteKeepsTheIncomeHeaderLinesAndDeductionDebtLegs() = runTest {
        val income = laterDeductionIncome()
        val rig = rig(income)
        val incomeRowBefore = rig.entries.rows.value.single { it.header.id == income.id }

        DeleteDebt(rig.runner, rig.debts, rig.deletionEntries(), rig.ledger)(debt.id)

        assertEquals(listOf(incomeRowBefore), rig.entries.rows.value)
        assertEquals(income, rig.entries.findById(income.id))
        assertEquals(income.deductions, (rig.entries.findById(income.id) as Transaction.Income).deductions)
        assertEquals(income.toParts().legs, rig.entries.rows.value.single().legs)
    }

    @Test fun undoRestoresTheDeletedEntriesAndLeavesTheIncomeUnchanged() = runTest {
        val income = laterDeductionIncome()
        val rig = rig(income)
        val rowsBefore = rig.entries.rows.value.toSet()

        val undo = DeleteDebt(rig.runner, rig.debts, rig.deletionEntries(), rig.ledger)(debt.id)
        rig.restoreEntries(undo)

        assertEquals(rowsBefore, rig.entries.rows.value.toSet())
        assertEquals(income, rig.entries.findById(income.id))
        assertEquals(debt, rig.debts.stored.single())
    }
}
