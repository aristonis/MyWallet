package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.service.DebtRemaining
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld.ali
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld.borrow
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld.repayment
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Undo returns a debt to the state it had before the delete, even when that state was below zero. */
class UndoToEarlierNegativeDebtTest {
    private fun negativeRig() = SaveEntryRig(initialDebts = listOf(ali),
        initialEntries = listOf(borrow(id = 1), repayment(id = 2, amount = "120")), atomic = true)

    private suspend fun remaining(rig: SaveEntryRig): Money =
        DebtRemaining.byDebt(rig.ledger.debtLegs(setOf(7))).getValue(7).remaining

    @Test fun undoOfAWholeDebtDeleteReturnsToTheNegativeRemaining() = runTest {
        val rig = negativeRig()
        val bundle = DeleteDebt(rig.runner, rig.debts, rig.deletionEntries(), rig.ledger)(7)
        assertEquals(Money.of("-20", "USD"), bundle.debtRemainingAtDelete.getValue(7))
        rig.restoreEntries(bundle)
        assertEquals(Money.of("-20", "USD"), remaining(rig))
        assertEquals(2, rig.entries.rows.value.size)
    }

    @Test fun undoOfDeletingTheOverpayingRepaymentReturnsToTheNegativeRemaining() = runTest {
        val rig = negativeRig()
        val bundle = rig.deleteEntry(2)
        assertEquals(Money.of("100", "USD"), remaining(rig))
        rig.restoreEntries(bundle)
        assertEquals(Money.of("-20", "USD"), remaining(rig))
        assertEquals(2, rig.entries.rows.value.size)
    }

    @Test fun undoThatEndsBelowTheValueCapturedAtDeleteIsStillRefused() = runTest {
        val rig = negativeRig()
        val bundle = rig.deleteEntry(2)
        rig.entries.insert(repayment(id = 3, amount = "10"))
        val failure = runCatching { rig.restoreEntries(bundle) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
        assertNull(rig.entries.findById(2))
        assertEquals(Money.of("90", "USD"), remaining(rig))
    }

    @Test fun aHealthyDebtsUndoStaysStrict() = runTest {
        val rig = SaveEntryRig(initialDebts = listOf(ali),
            initialEntries = listOf(borrow(id = 1), repayment(id = 2, amount = "50")), atomic = true)
        val bundle = rig.deleteEntry(2)
        assertEquals(Money.of("50", "USD"), bundle.debtRemainingAtDelete.getValue(7))
        rig.entries.insert(repayment(id = 3, amount = "80"))
        val failure = runCatching { rig.restoreEntries(bundle) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
        assertNull(rig.entries.findById(2))
    }

    @Test fun aBundleWithoutCapturedRemainingsKeepsTheStrictRule() = runTest {
        val rig = negativeRig()
        val bundle = rig.deleteEntry(2).copy(debtRemainingAtDelete = emptyMap())
        val failure = runCatching { rig.restoreEntries(bundle) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
    }
}
