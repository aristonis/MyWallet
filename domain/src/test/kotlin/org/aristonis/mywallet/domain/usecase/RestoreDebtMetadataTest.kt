package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.UndoBundle
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreDebtMetadataTest {
    @Test fun undoRestoresOnlyMetadataFieldsThatAreStillEmptyOnBothSides() = runTest {
        DebtSide.entries.forEach { side ->
            val original = DebtEntryWorld.ali.copy(side = side, dueDate = EntryWorld.day, note = "original agreement")
            val loan = deletionOpening(original)
            val cases = listOf(null to null, EntryWorld.day.plusDays(7) to null,
                null to "new agreement", EntryWorld.day.plusDays(7) to "new agreement", null to "")
            cases.forEach { (newDate, newNote) ->
                val rig = SaveEntryRig(initialDebts = listOf(original), initialEntries = listOf(loan), atomic = true)
                val bundle = DeleteDebt(rig.runner, rig.debts, rig.deletionEntries(), rig.ledger)(original.id)
                rig.debts.updateDetails(original.id, newDate, newNote)
                rig.restoreEntries(bundle)
                assertEquals(original.copy(dueDate = newDate ?: original.dueDate, note = newNote ?: original.note),
                    rig.debts.findById(original.id))
                assertEquals(loan, rig.entries.findById(loan.id))
            }
        }
    }

    @Test fun aTakenEntryIdLeavesNewerDetailsAndEarlierRestoredEntriesUntouched() = runTest {
        val original = DebtEntryWorld.ali.copy(dueDate = EntryWorld.day, note = "old")
        val newer = original.copy(dueDate = null, note = "new")
        val occupant = EntryWorld.expense(id = 2)
        val rig = SaveEntryRig(initialDebts = listOf(newer), initialEntries = listOf(occupant), atomic = true)
        val bundle = UndoBundle(listOf(deletionOpening(original), DebtEntryWorld.repayment(id = 2)), listOf(original))
        val failure = runCatching { rig.restoreEntries(bundle) }.exceptionOrNull()
        assertTrue(failure is WalletException.EntryIdTaken)
        assertEquals(newer, rig.debts.findById(original.id))
        assertNull(rig.entries.findById(1))
        assertEquals(occupant, rig.entries.findById(2))
    }
}
