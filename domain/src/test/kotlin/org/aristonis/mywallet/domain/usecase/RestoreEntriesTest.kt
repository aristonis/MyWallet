package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
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
    fun anItemDeletedWhileItsSalaryWasUndoneRefusesTheWholeRestore() = runTest {
        val rig = salaryRig()
        val id = rig.saveEntry(salary(), emptyMap()).id
        val bundle = rig.deleteEntry(id)
        rig.deductions.delete(10)
        val fine = EntryWorld.expense(id = 80, rate = "1")
        val failure = runCatching {
            rig.restoreEntries(bundle.copy(entries = listOf(fine) + bundle.entries))
        }.exceptionOrNull()

        assertTrue("was $failure", failure is WalletException.DeductionItemNotFound)
        assertEquals(10L, (failure as WalletException.DeductionItemNotFound).id)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test
    fun aCategoryThatBecameASubCategoryRefusesTheRestore() = runTest {
        val income = EntryWorld.income(id = 4, rate = "1")
        val reparented = listOf(
            Category(id = 4, name = "Work", kind = CategoryKind.INCOME),
            Category(id = EntryWorld.incomeCategory.id, name = "Salary", kind = CategoryKind.INCOME, parentId = 4),
        )
        val rig = SaveEntryRig(categories = reparented, atomic = true)

        val failure = runCatching { rig.restoreEntries(bundleOf(income)) }.exceptionOrNull()

        assertTrue("was $failure", failure is WalletException.CategoryNotTopLevel)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test
    fun aTransferIsRestoredEvenWithNoCategoriesAtAll() = runTest {
        // Transfers are never categorized, so the category check must not stand in their way.
        val transfer = EntryWorld.transfer(id = 9, sourceAccountId = 1, destAccountId = 3)
        val rig = SaveEntryRig(categories = emptyList(), atomic = true)

        rig.restoreEntries(bundleOf(transfer))

        assertEquals(transfer, rig.entries.findById(9))
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
