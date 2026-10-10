package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.UndoBundle
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld.ali
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld.borrow
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld.repayment
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.*
import org.junit.Test

class RestoreEntriesDebtTest {
    @Test fun undoOfARepaymentThatWouldOverpayIsRefused() = runTest {
        val existing = repayment(id = 2, amount = "100")
        val restored = repayment(id = 3, amount = "100")
        val rig = SaveEntryRig(initialDebts = listOf(ali), initialEntries = listOf(borrow(id = 1), existing), atomic = true)
        val failure = runCatching { rig.restoreEntries(UndoBundle(listOf(restored))) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
        assertNull(rig.entries.findById(3))
        assertEquals(existing, rig.entries.findById(2))
    }

    @Test fun undoChecksDebtReferencesBeforeReinserting() = runTest {
        val rig = SaveEntryRig(atomic = true)
        val failure = runCatching { rig.restoreEntries(UndoBundle(listOf(borrow(id = 1)))) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtNotFound)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test fun undoChecksDebtSideBeforeReinserting() = runTest {
        val rig = SaveEntryRig(initialDebts = listOf(ali.copy(side = DebtSide.OWED_TO_ME)), atomic = true)
        val failure = runCatching { rig.restoreEntries(UndoBundle(listOf(borrow(id = 1)))) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtSideMismatch)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test fun undoChecksDebtCurrencyBeforeReinserting() = runTest {
        val rig = SaveEntryRig(initialDebts = listOf(ali.copy(currencyCode = "SYP")), atomic = true)
        val failure = runCatching { rig.restoreEntries(UndoBundle(listOf(borrow(id = 1)))) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.CurrencyMismatch)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test fun undoRestoresTheDebtDetailsCarriedByTheBundle() = runTest {
        val snapshot = ali.copy(dueDate = EntryWorld.day, note = "before deletion")
        val rig = SaveEntryRig(initialDebts = listOf(ali), atomic = true)
        rig.restoreEntries(UndoBundle(entries = listOf(borrow(id = 1)), debts = listOf(snapshot)))
        assertEquals(snapshot, rig.debts.findById(7))
    }

    @Test fun aTakenIdAlsoRollsBackRestoredDebtDetailsAndEarlierEntries() = runTest {
        val snapshot = ali.copy(dueDate = EntryWorld.day, note = "old")
        val occupant = EntryWorld.expense(id = 2)
        val rig = SaveEntryRig(initialDebts = listOf(ali), initialEntries = listOf(occupant), atomic = true)
        val failure = runCatching {
            rig.restoreEntries(UndoBundle(listOf(borrow(id = 1), repayment(id = 2)), listOf(snapshot)))
        }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.EntryIdTaken)
        assertEquals(ali, rig.debts.findById(7))
        assertNull(rig.entries.findById(1))
        assertEquals(occupant, rig.entries.findById(2))
    }

    @Test fun undoNeverRecreatesAMissingDebtFromItsSnapshot() = runTest {
        val rig = SaveEntryRig(atomic = true)
        val failure = runCatching { rig.restoreEntries(UndoBundle(listOf(borrow(id = 1)), listOf(ali))) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtNotFound)
        assertTrue(rig.debts.stored.isEmpty())
        assertTrue(rig.entries.rows.value.isEmpty())
    }
    @Test fun anAccountCurrencyChangedSinceDeletionRefusesUndoWithoutRestoringDebtDetails() = runTest {
        val detailed = ali.copy(dueDate = EntryWorld.day, note = "original details")
        val loan = borrow(id = 1)
        val rig = SaveEntryRig(initialDebts = listOf(detailed), initialEntries = listOf(loan), atomic = true)
        val bundle = rig.deleteEntry(1)
        rig.accountRepository.upsert(EntryWorld.usdAccount.copy(currencyCode = "SYP",
            openingBalance = org.aristonis.mywallet.domain.model.Money.zero("SYP")))
        val failure = runCatching { rig.restoreEntries(bundle) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.CurrencyMismatch)
        assertTrue(rig.entries.rows.value.isEmpty())
        assertEquals(detailed, rig.debts.findById(7))
    }

    @Test fun aFinalBelowZeroGuardRollsBackRestoredDebtDetailsAndEveryRestoredEntry() = runTest {
        val current = ali.copy(dueDate = EntryWorld.day.plusDays(7), note = "current agreement")
        val snapshot = ali.copy(dueDate = EntryWorld.day, note = "old agreement")
        val existing = repayment(id = 2, amount = "100")
        val rig = SaveEntryRig(initialDebts = listOf(current), initialEntries = listOf(borrow(id = 1), existing), atomic = true)
        val bundle = UndoBundle(entries = listOf(EntryWorld.expense(id = 3), repayment(id = 4, amount = "50")), debts = listOf(snapshot))
        val failure = runCatching { rig.restoreEntries(bundle) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
        assertEquals(current, rig.debts.findById(7))
        assertNull(rig.entries.findById(3))
        assertNull(rig.entries.findById(4))
        assertEquals(existing, rig.entries.findById(2))
        assertEquals(2, rig.entries.rows.value.size)
    }

}
