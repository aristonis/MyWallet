package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DeductionInput
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.service.CurrencyConverter
import org.aristonis.mywallet.domain.service.DeductionCalculator
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Deleting an owed-later debt preserves the income that created it through the real save and recipe paths. */
class DebtDeletionRealIncomeTest {
    private data class SavedIncomeDebt(
        val rig: SaveEntryRig,
        val income: Transaction.Income,
        val debt: Debt,
        val ownEntryIds: Set<Long>,
    )

    private suspend fun savedIncomeDebt(): SavedIncomeDebt {
        val rig = salaryRig()
        val draft = salary()
        val inputs = draft.deductions.map {
            DeductionInput(it.mode, it.value, it.valueCurrencyCode, it.timing)
        }
        val calculation = DeductionCalculator.calculate(
            draft.amount, inputs, CurrencyConverter("USD", emptyMap(), mapOf("USD" to 2)),
        )
        val calculated = draft.copy(deductions = draft.deductions.zip(calculation.lines) { line, result ->
            line.copy(amount = result.amount)
        })
        val incomeId = rig.saveEntry(calculated, emptyMap()).id
        val income = rig.entries.findById(incomeId) as Transaction.Income
        val debtId = requireNotNull(income.deductions.first().debtId)
        rig.debts.updateDetails(debtId, deletionDay.plusDays(7), "tax due")
        val debt = requireNotNull(rig.debts.findById(debtId))
        val openingId = rig.saveEntry(deletionOpening(debt, id = 0), emptyMap()).id
        val paymentId = rig.saveEntry(deletionPayment(debt, id = 0), emptyMap()).id
        assertEquals(Money.of("980", "USD"), calculation.accountReceives)
        assertEquals(3, income.deductions.size)
        assertEquals(2, income.deductions.count { it.timing == DeductionTiming.LATER && it.debtId != null })
        return SavedIncomeDebt(rig, income, debt, setOf(openingId, paymentId))
    }

    @Test fun previewListsSavedIncomeAndCountsItsOwedLaterDeductionInTheRemainder() = runTest {
        val (rig, income, debt, ownEntryIds) = savedIncomeDebt()
        val before = rig.entries.rows.value

        val preview = PreviewDebtDeletion(rig.runner, rig.debts, rig.deletionEntries(), rig.ledger)(debt.id)

        assertEquals(listOf(income.id), preview.retainedEntryIds)
        assertEquals(ownEntryIds, preview.entries.map { it.id }.toSet())
        assertEquals(Money.of("100", "USD"), preview.remainingAfter)
        assertEquals(before, rig.entries.rows.value)
    }

    @Test fun deleteKeepsSavedIncomeHeaderAllLinesAndBothDeductionDebtLegs() = runTest {
        val (rig, income, debt) = savedIncomeDebt()
        val incomeRowBefore = rig.entries.rows.value.single { it.header.id == income.id }
        assertEquals(2, incomeRowBefore.legs.count { it.role == LegRole.DEDUCTION_DEBT })

        DeleteDebt(rig.runner, rig.debts, rig.deletionEntries(), rig.ledger)(debt.id)

        assertEquals(listOf(incomeRowBefore), rig.entries.rows.value)
        assertEquals(income, rig.entries.findById(income.id))
        assertEquals(income.deductions, (rig.entries.findById(income.id) as Transaction.Income).deductions)
        val keptDebt = requireNotNull(rig.debts.findById(debt.id))
        assertNull(keptDebt.dueDate)
        assertNull(keptDebt.note)
    }

    @Test fun undoRestoresOwnEntriesAndOldDebtDetailsWithoutChangingSavedIncome() = runTest {
        val (rig, income, debt, ownEntryIds) = savedIncomeDebt()
        val rowsBefore = rig.entries.rows.value.toSet()

        val undo = DeleteDebt(rig.runner, rig.debts, rig.deletionEntries(), rig.ledger)(debt.id)
        assertEquals(ownEntryIds, undo.entries.map { it.id }.toSet())
        rig.restoreEntries(undo)

        assertEquals(rowsBefore, rig.entries.rows.value.toSet())
        assertEquals(income, rig.entries.findById(income.id))
        assertEquals(debt, rig.debts.findById(debt.id))
    }
}
