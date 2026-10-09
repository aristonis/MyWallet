package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.model.UndoBundle
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreEntriesTest {

    private fun bundleOf(vararg entries: Transaction) = UndoBundle(entries = entries.toList(), debts = emptyList())

    @Test
    fun deleteThenRestoreBringsTheEntryBackAtItsOriginalId() = runTest {
        val stored = EntryWorld.expense(id = 4, amount = "12.50", rate = "1", note = "lunch")
        val rig = SaveEntryRig(initialEntries = listOf(stored), atomic = true)

        val bundle = rig.deleteEntry(4)
        rig.restoreEntries(bundle)

        assertEquals(stored, rig.entries.findById(4))
        assertEquals(1, rig.entries.rows.value.size)
    }

    @Test
    fun restoresEveryEntryOfTheBundleAtItsOwnId() = runTest {
        val first = EntryWorld.expense(id = 4, rate = "1")
        val second = EntryWorld.income(id = 8, rate = "1")
        val rig = SaveEntryRig(atomic = true)

        rig.restoreEntries(bundleOf(first, second))

        assertEquals(first, rig.entries.findById(4))
        assertEquals(second, rig.entries.findById(8))
    }

    @Test
    fun aTakenIdAbortsAndNothingIsRestored() = runTest {
        val free = EntryWorld.expense(id = 4, rate = "1")
        val taken = EntryWorld.expense(id = 8, rate = "1", note = "restored copy")
        val occupant = EntryWorld.income(id = 8, rate = "1")
        val rig = SaveEntryRig(initialEntries = listOf(occupant), atomic = true)

        val failure = runCatching { rig.restoreEntries(bundleOf(free, taken)) }.exceptionOrNull()

        assertTrue("was $failure", failure is WalletException.EntryIdTaken)
        assertEquals(8L, (failure as WalletException.EntryIdTaken).id)
        assertEquals(listOf(8L), rig.entries.rows.value.map { it.header.id })
        assertEquals(occupant, rig.entries.findById(8))
    }

    @Test
    fun aDeletedCategoryRefusesTheRestore() = runTest {
        val gone = EntryWorld.expense(id = 4, rate = "1", categoryId = 77)
        val rig = SaveEntryRig(atomic = true)

        val failure = runCatching { rig.restoreEntries(bundleOf(gone)) }.exceptionOrNull()

        assertTrue("was $failure", failure is WalletException.CategoryNotFound)
        assertEquals(77L, (failure as WalletException.CategoryNotFound).id)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test
    fun aDeletedCategoryOnALaterEntryStopsTheWholeRestore() = runTest {
        val fine = EntryWorld.expense(id = 4, rate = "1")
        val gone = EntryWorld.expense(id = 5, rate = "1", categoryId = 77)
        val rig = SaveEntryRig(atomic = true)

        runCatching { rig.restoreEntries(bundleOf(fine, gone)) }

        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test
    fun restoreNeverOverwritesByReplacing() = runTest {
        val occupant = EntryWorld.expense(id = 4, rate = "1", note = "mine")
        val rig = SaveEntryRig(initialEntries = listOf(occupant), atomic = true)

        runCatching { rig.restoreEntries(bundleOf(EntryWorld.expense(id = 4, rate = "1", note = "other"))) }

        assertEquals(occupant, rig.entries.findById(4))
    }

    @Test
    fun anEntryWhoseAccountIsGoneRefusesTheRestoreAndNothingComesBack() = runTest {
        val fine = EntryWorld.expense(id = 4, rate = "1")
        val orphan = EntryWorld.expense(id = 5, accountId = 77, rate = "1")
        val rig = SaveEntryRig(atomic = true)

        val failure = runCatching { rig.restoreEntries(bundleOf(fine, orphan)) }.exceptionOrNull()

        assertTrue("was $failure", failure is WalletException.AccountNotFound)
        assertEquals(77L, (failure as WalletException.AccountNotFound).id)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test
    fun aTransferWhoseDestinationAccountIsGoneRefusesTheRestore() = runTest {
        val transfer = EntryWorld.transfer(id = 9, sourceAccountId = 1, destAccountId = 77)
        val rig = SaveEntryRig(atomic = true)

        val failure = runCatching { rig.restoreEntries(bundleOf(transfer)) }.exceptionOrNull()

        assertTrue("was $failure", failure is WalletException.AccountNotFound)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test
    fun anEmptyBundleChangesNothing() = runTest {
        val stored = EntryWorld.expense(id = 4, rate = "1")
        val rig = SaveEntryRig(initialEntries = listOf(stored), atomic = true)

        rig.restoreEntries(bundleOf())

        assertEquals(listOf<Transaction>(stored), rig.entries.observeAllForTest())
    }

    @Test
    fun anEntryWithIdZeroIsRefusedAndNothingIsRestored() = runTest {
        val fine = EntryWorld.expense(id = 4, rate = "1")
        val unsaved = EntryWorld.expense(id = 0, rate = "1")
        val rig = SaveEntryRig(atomic = true)

        val failure = runCatching { rig.restoreEntries(bundleOf(fine, unsaved)) }.exceptionOrNull()

        assertTrue("an unsaved entry is not an undo", failure != null)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    private suspend fun org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository.observeAllForTest() =
        observeAll().first()
}
