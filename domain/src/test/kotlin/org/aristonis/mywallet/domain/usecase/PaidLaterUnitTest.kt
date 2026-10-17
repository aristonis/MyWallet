package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.SaveEntryPaidLaterTest.Companion.purchase
import org.aristonis.mywallet.domain.usecase.SaveEntryPaidLaterTest.Companion.shop
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld.repayment
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.*
import org.junit.Test

class PaidLaterUnitTest {
    @Test fun deleteAndUndoRestoreEveryLegAndDebtDetailsExactly() = runTest {
        val rig = SaveEntryRig(atomic = true)
        val id = rig.saveEntry(purchase(), emptyMap(), shop).id
        val stored = rig.entries.findById(id) as Transaction.Expense
        val debtId = stored.owedDebtId!!
        val due = stored.date.plusDays(10)
        rig.debts.updateDetails(debtId, due, "keep")
        val before = rig.entries.rows.value.single()
        val debtBefore = rig.debts.findById(debtId)
        val bundle = rig.deleteEntry(id)
        assertTrue(rig.entries.rows.value.isEmpty())
        rig.restoreEntries(bundle)
        assertEquals(before, rig.entries.rows.value.single())
        assertEquals(debtBefore, rig.debts.findById(debtId))
    }

    @Test fun deletingPurchaseWithRepaymentRefusesAndPreservesBothEntries() = runTest {
        val rig = SaveEntryRig(atomic = true)
        val id = rig.saveEntry(purchase(), emptyMap(), shop).id
        val debtId = (rig.entries.findById(id) as Transaction.Expense).owedDebtId!!
        rig.saveEntry(repayment(debtId = debtId, amount = "600"), emptyMap())
        val before = rig.entries.rows.value
        val failure = runCatching { rig.deleteEntry(id) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
        assertEquals(before, rig.entries.rows.value)
    }
}
