package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.service.DebtRemaining
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld.ali
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld.borrow
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld.lend
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld.repayment
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld.sara
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld.usd
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.*
import org.junit.Test

class DeleteEntryDebtTest {
    @Test fun deletingARepaidLoanIsRefused() = runTest {
        val loan = borrow(id = 1)
        val paid = repayment(id = 2, amount = "100")
        val rig = SaveEntryRig(initialDebts = listOf(ali), initialEntries = listOf(loan, paid), atomic = true)
        val failure = runCatching { rig.deleteEntry(1) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
        assertEquals(loan, rig.entries.findById(1))
        assertEquals(paid, rig.entries.findById(2))
    }

    @Test fun deletingEarnedIncomeAfterItsDebtWasCollectedIsRefused() = runTest {
        val income = Transaction.EarnedUnpaid(id = 1, debtId = 8, amount = usd("100"), categoryId = 5, date = EntryWorld.day)
        val paid = repayment(id = 2, debtId = 8, side = DebtSide.OWED_TO_ME, amount = "100")
        val rig = SaveEntryRig(initialDebts = listOf(sara), initialEntries = listOf(income, paid), atomic = true)
        val failure = runCatching { rig.deleteEntry(1) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
        assertEquals(income, rig.entries.findById(1))
        assertEquals(paid, rig.entries.findById(2))
    }

    @Test fun deletingARepaymentRaisesTheRemainingAndUndoSettlesItAgain() = runTest {
        val paid = repayment(id = 2, amount = "100")
        val rig = SaveEntryRig(initialDebts = listOf(ali), initialEntries = listOf(borrow(id = 1), paid), atomic = true)
        val bundle = rig.deleteEntry(2)
        assertEquals(listOf(paid), bundle.entries)
        assertEquals(usd("100"), DebtRemaining.byDebt(rig.ledger.debtLegs(setOf(7))).getValue(7).remaining)
        rig.restoreEntries(bundle)
        assertEquals(paid, rig.entries.findById(2))
        assertEquals(usd("0"), DebtRemaining.byDebt(rig.ledger.debtLegs(setOf(7))).getValue(7).remaining)
    }

    @Test fun deletingAWriteOffReopensTheDebtWithItsDetailsKept() = runTest {
        val detailed = sara.copy(dueDate = EntryWorld.day, note = "keep details")
        val writtenOff = Transaction.WriteOff(id = 2, side = DebtSide.OWED_TO_ME, debtId = 8, amount = usd("100"), categoryId = 6, date = EntryWorld.day)
        val rig = SaveEntryRig(initialDebts = listOf(detailed), initialEntries = listOf(lend(id = 1), writtenOff), atomic = true)
        val bundle = rig.deleteEntry(2)
        assertEquals(usd("100"), DebtRemaining.byDebt(rig.ledger.debtLegs(setOf(8))).getValue(8).remaining)
        assertEquals(detailed, rig.debts.findById(8))
        rig.restoreEntries(bundle)
        assertEquals(writtenOff, rig.entries.findById(2))
        assertEquals(detailed, rig.debts.findById(8))
    }

    @Test fun deletingAnUnrepaidLoanKeepsItsDebtRow() = runTest {
        val rig = SaveEntryRig(initialDebts = listOf(ali), initialEntries = listOf(borrow(id = 1)), atomic = true)
        rig.deleteEntry(1)
        assertTrue(rig.entries.rows.value.isEmpty())
        assertEquals(ali, rig.debts.findById(7))
    }
    @Test fun deletingACreditPurchaseRemovesItsDebtAndExpenseLegsTogether() = runTest {
        val entry = Transaction.CreditPurchase(id = 1, debtId = 7, amount = usd("20"), categoryId = 6, date = EntryWorld.day)
        val rig = SaveEntryRig(initialDebts = listOf(ali), initialEntries = listOf(entry), atomic = true)
        val bundle = rig.deleteEntry(1)
        assertEquals(listOf(entry), bundle.entries)
        assertTrue(rig.entries.rows.value.isEmpty())
        assertTrue(rig.ledger.debtLegs(setOf(7)).isEmpty())
        assertEquals(ali, rig.debts.findById(7))
    }

    @Test fun undoOfAnOrdinaryRepaymentDeletionKeepsDetailsChangedWhileUndoWasOffered() = runTest {
        val paid = repayment(id = 2, amount = "100")
        val rig = SaveEntryRig(initialDebts = listOf(ali), initialEntries = listOf(borrow(id = 1), paid), atomic = true)
        val bundle = rig.deleteEntry(2)
        assertTrue(bundle.debts.isEmpty())
        val newDate = EntryWorld.day.plusDays(5)
        rig.debts.updateDetails(7, newDate, "new agreement")
        rig.restoreEntries(bundle)
        assertEquals(paid, rig.entries.findById(2))
        assertEquals(ali.copy(dueDate = newDate, note = "new agreement"), rig.debts.findById(7))
    }

    @Test fun undoOfAWriteOffDeletionKeepsDetailsChangedWhileUndoWasOffered() = runTest {
        val writtenOff = Transaction.WriteOff(id = 2, side = DebtSide.OWED_TO_ME, debtId = 8, amount = usd("100"), categoryId = 6, date = EntryWorld.day)
        val rig = SaveEntryRig(initialDebts = listOf(sara), initialEntries = listOf(lend(id = 1), writtenOff), atomic = true)
        val bundle = rig.deleteEntry(2)
        assertTrue(bundle.debts.isEmpty())
        val newDate = EntryWorld.day.plusDays(5)
        rig.debts.updateDetails(8, newDate, "new agreement")
        rig.restoreEntries(bundle)
        assertEquals(writtenOff, rig.entries.findById(2))
        assertEquals(sara.copy(dueDate = newDate, note = "new agreement"), rig.debts.findById(8))
    }

}
