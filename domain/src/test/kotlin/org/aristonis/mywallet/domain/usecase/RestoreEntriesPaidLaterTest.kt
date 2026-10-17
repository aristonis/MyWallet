package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.model.UndoBundle
import org.aristonis.mywallet.domain.usecase.SaveEntryPaidLaterTest.Companion.purchase
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld.ali
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.*
import org.junit.Test

class RestoreEntriesPaidLaterTest {
    @Test fun missingCategoryRestoresBothExpensePartsOnSameFallbackWithoutLosingDebt() = runTest {
        val entry = purchase().copy(id = 4, owedDebtId = ali.id)
        val rig = SaveEntryRig(initialDebts = listOf(ali), categories = emptyList(), atomic = true)
        rig.restoreEntries(UndoBundle(entries = listOf(entry), debts = emptyList()))
        val legs = rig.entries.rows.value.single().legs
        assertEquals(legs.single { it.role == LegRole.MAIN }.target, legs.single { it.role == LegRole.PAID_LATER }.target)
        assertTrue(legs.single { it.role == LegRole.PAID_LATER }.target is LegTarget.Category)
        assertEquals(entry.owed, legs.single { it.role == LegRole.DEBT }.amount)
        val restored = rig.entries.findById(4) as Transaction.Expense
        assertEquals(entry.owed, restored.owed)
        assertEquals(entry.owedDebtId, restored.owedDebtId)
        assertEquals(entry.amount, restored.amount)
    }
}
