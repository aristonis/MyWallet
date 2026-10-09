package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every reference an entry names is checked on a new save and on an edit, and a refusal stores nothing. */
class SaveEntryRefusalTest {

    private val categories = listOf(
        EntryWorld.incomeCategory,
        EntryWorld.expenseCategory,
        EntryWorld.foodSubCategory,
        EntryWorld.salarySubCategory,
    )

    private val gone = 99L
    private val archivedUsd = EntryWorld.usdAccount.copy(archived = true)
    private val archivedSecondUsd = EntryWorld.secondUsdAccount.copy(archived = true)

    private suspend fun SaveEntryRig.refusal(entry: Transaction): Throwable? =
        runCatching { saveEntry(entry, typedRates = emptyMap()) }.exceptionOrNull()

    private fun rigWith(
        stored: Transaction? = null,
        accounts: List<org.aristonis.mywallet.domain.model.Account> =
            listOf(EntryWorld.usdAccount, EntryWorld.sypAccount, EntryWorld.secondUsdAccount),
    ) = SaveEntryRig(initialEntries = listOfNotNull(stored), categories = categories, accounts = accounts)

    private fun assertUntouched(rig: SaveEntryRig, stored: Transaction?) {
        assertEquals(listOfNotNull(stored).map { it.id }, rig.entries.rows.value.map { it.header.id })
    }

    // --- accounts: new ---

    @Test
    fun aNewIncomeOnAnUnknownAccountIsRefused() = runTest {
        val rig = rigWith()
        val failure = rig.refusal(EntryWorld.income(accountId = gone))
        assertTrue("was $failure", failure is WalletException.AccountNotFound)
        assertUntouched(rig, null)
    }

    @Test
    fun aNewExpenseOnAnUnknownAccountIsRefused() = runTest {
        val rig = rigWith()
        val failure = rig.refusal(EntryWorld.expense(accountId = gone))
        assertTrue("was $failure", failure is WalletException.AccountNotFound)
        assertUntouched(rig, null)
    }

    @Test
    fun aNewTransferFromAnUnknownAccountIsRefused() = runTest {
        val rig = rigWith()
        val failure = rig.refusal(EntryWorld.transfer(sourceAccountId = gone, destAccountId = 3))
        assertTrue("was $failure", failure is WalletException.AccountNotFound)
        assertUntouched(rig, null)
    }

    @Test
    fun aNewTransferToAnUnknownAccountIsRefused() = runTest {
        val rig = rigWith()
        val failure = rig.refusal(EntryWorld.transfer(sourceAccountId = 1, destAccountId = gone))
        assertTrue("was $failure", failure is WalletException.AccountNotFound)
        assertUntouched(rig, null)
    }

    // --- accounts: archived, new ---

    @Test
    fun aNewIncomeOnAnArchivedAccountIsRefused() = runTest {
        val rig = rigWith(accounts = listOf(archivedUsd, EntryWorld.sypAccount))
        val failure = rig.refusal(EntryWorld.income())
        assertTrue("was $failure", failure is WalletException.AccountArchived)
        assertUntouched(rig, null)
    }

    @Test
    fun aNewExpenseOnAnArchivedAccountIsRefused() = runTest {
        val rig = rigWith(accounts = listOf(archivedUsd, EntryWorld.sypAccount))
        val failure = rig.refusal(EntryWorld.expense())
        assertTrue("was $failure", failure is WalletException.AccountArchived)
        assertUntouched(rig, null)
    }

    @Test
    fun aNewTransferFromAnArchivedAccountIsRefused() = runTest {
        val rig = rigWith(accounts = listOf(archivedUsd, EntryWorld.secondUsdAccount))
        val failure = rig.refusal(EntryWorld.transfer(sourceAccountId = 1, destAccountId = 3))
        assertTrue("was $failure", failure is WalletException.AccountArchived)
        assertUntouched(rig, null)
    }

    @Test
    fun aNewTransferToAnArchivedAccountIsRefused() = runTest {
        val rig = rigWith(accounts = listOf(EntryWorld.usdAccount, archivedSecondUsd))
        val failure = rig.refusal(EntryWorld.transfer(sourceAccountId = 1, destAccountId = 3))
        assertTrue("was $failure", failure is WalletException.AccountArchived)
        assertUntouched(rig, null)
    }

    // --- accounts: edit ---

    @Test
    fun anIncomeEditedOntoAnUnknownAccountIsRefusedAndTheStoredEntryStays() = runTest {
        val stored = EntryWorld.income(id = 4, rate = "1")
        val rig = rigWith(stored)
        val failure = rig.refusal(EntryWorld.income(id = 4, accountId = gone, rate = "1"))
        assertTrue("was $failure", failure is WalletException.AccountNotFound)
        assertEquals(stored, rig.entries.findById(4))
    }

    @Test
    fun anExpenseEditedOntoAnArchivedAccountIsRefusedAndTheStoredEntryStays() = runTest {
        val stored = EntryWorld.expense(id = 4, accountId = 3, rate = "1")
        val rig = rigWith(stored, accounts = listOf(EntryWorld.usdAccount, EntryWorld.sypAccount, archivedUsd.copy(id = 3)))
        val failure = rig.refusal(EntryWorld.expense(id = 4, accountId = 3, rate = "1", note = "edited"))
        assertTrue("was $failure", failure is WalletException.AccountArchived)
        assertEquals(stored, rig.entries.findById(4))
    }

    @Test
    fun aTransferEditedWithAnUnknownSourceIsRefused() = runTest {
        val stored = EntryWorld.transfer(id = 9, sourceAccountId = 1, destAccountId = 3)
        val rig = rigWith(stored)
        val failure = rig.refusal(EntryWorld.transfer(id = 9, sourceAccountId = gone, destAccountId = 3))
        assertTrue("was $failure", failure is WalletException.AccountNotFound)
        assertEquals(stored, rig.entries.findById(9))
    }

    @Test
    fun aTransferEditedWithAnUnknownDestinationIsRefused() = runTest {
        val stored = EntryWorld.transfer(id = 9, sourceAccountId = 1, destAccountId = 3)
        val rig = rigWith(stored)
        val failure = rig.refusal(EntryWorld.transfer(id = 9, sourceAccountId = 1, destAccountId = gone))
        assertTrue("was $failure", failure is WalletException.AccountNotFound)
        assertEquals(stored, rig.entries.findById(9))
    }

    @Test
    fun aTransferEditedWithAnArchivedSourceIsRefused() = runTest {
        val stored = EntryWorld.transfer(id = 9, sourceAccountId = 1, destAccountId = 3)
        val rig = rigWith(stored, accounts = listOf(archivedUsd, EntryWorld.secondUsdAccount))
        val failure = rig.refusal(EntryWorld.transfer(id = 9, sourceAccountId = 1, destAccountId = 3, amount = "5"))
        assertTrue("was $failure", failure is WalletException.AccountArchived)
        assertEquals(stored, rig.entries.findById(9))
    }

    @Test
    fun aTransferEditedWithAnArchivedDestinationIsRefused() = runTest {
        val stored = EntryWorld.transfer(id = 9, sourceAccountId = 1, destAccountId = 3)
        val rig = rigWith(stored, accounts = listOf(EntryWorld.usdAccount, archivedSecondUsd))
        val failure = rig.refusal(EntryWorld.transfer(id = 9, sourceAccountId = 1, destAccountId = 3, amount = "5"))
        assertTrue("was $failure", failure is WalletException.AccountArchived)
        assertEquals(stored, rig.entries.findById(9))
    }

    @Test
    fun anExpenseEditedToAnAmountInAnotherCurrencyThanItsAccountIsRefused() = runTest {
        val stored = EntryWorld.expense(id = 4, rate = "1")
        val rig = rigWith(stored)
        val failure = rig.refusal(EntryWorld.expense(id = 4, accountId = 1, amount = "5", currency = "SYP", rate = "1"))
        assertTrue("was $failure", failure is WalletException.CurrencyMismatch)
        assertEquals(stored, rig.entries.findById(4))
    }

    @Test
    fun aNewIncomeInAnotherCurrencyThanItsAccountIsRefused() = runTest {
        val rig = rigWith()
        val failure = rig.refusal(EntryWorld.income(accountId = 1, amount = "5", currency = "SYP"))
        assertTrue("was $failure", failure is WalletException.CurrencyMismatch)
        assertUntouched(rig, null)
    }

    @Test
    fun aNewTransferWhoseAmountIsInAnotherCurrencyThanItsSourceIsRefused() = runTest {
        // A SYP amount from the USD account into the SYP account: the source leg is in the wrong currency.
        val rig = rigWith()
        val failure = rig.refusal(EntryWorld.transfer(sourceAccountId = 1, destAccountId = 2, amount = "5", from = "SYP"))
        assertTrue("was $failure", failure is WalletException.CurrencyMismatch)
        assertUntouched(rig, null)
    }

    // --- categories ---

    @Test
    fun aNewExpenseOnAnUnknownCategoryIsRefused() = runTest {
        val rig = rigWith()
        val failure = rig.refusal(EntryWorld.expense(categoryId = 404))
        assertTrue("was $failure", failure is WalletException.CategoryNotFound)
        assertUntouched(rig, null)
    }

    @Test
    fun anExpenseEditedOntoAnUnknownCategoryIsRefused() = runTest {
        val stored = EntryWorld.expense(id = 4, rate = "1")
        val rig = rigWith(stored)
        val failure = rig.refusal(EntryWorld.expense(id = 4, categoryId = 404, rate = "1"))
        assertTrue("was $failure", failure is WalletException.CategoryNotFound)
        assertEquals(stored, rig.entries.findById(4))
    }

    @Test
    fun aNewIncomeOnAnUnknownCategoryIsRefused() = runTest {
        val rig = rigWith()
        val failure = rig.refusal(EntryWorld.income(categoryId = 404))
        assertTrue("was $failure", failure is WalletException.CategoryNotFound)
        assertUntouched(rig, null)
    }

    @Test
    fun aNewExpenseWithASubCategoryOfAnotherCategoryIsRefused() = runTest {
        val rig = rigWith()
        val failure = rig.refusal(EntryWorld.expense(subCategoryId = EntryWorld.salarySubCategory.id))
        assertTrue("was $failure", failure is WalletException.CategoryKindMismatch)
        assertUntouched(rig, null)
    }

    @Test
    fun anExpenseEditedToASubCategoryOfAnotherCategoryIsRefused() = runTest {
        val stored = EntryWorld.expense(id = 4, rate = "1")
        val rig = rigWith(stored)
        val failure = rig.refusal(
            EntryWorld.expense(id = 4, rate = "1", subCategoryId = EntryWorld.salarySubCategory.id),
        )
        assertTrue("was $failure", failure is WalletException.CategoryKindMismatch)
        assertEquals(stored, rig.entries.findById(4))
    }

    @Test
    fun aSubCategoryUnderItsOwnCategoryIsAccepted() = runTest {
        val rig = rigWith()
        val saved = rig.saveEntry(EntryWorld.expense(subCategoryId = EntryWorld.foodSubCategory.id), emptyMap())
        assertEquals(
            EntryWorld.foodSubCategory.id,
            (rig.entries.findById(saved.id) as Transaction.Expense).subCategoryId,
        )
    }

    // --- edits that are fine ---

    @Test
    fun editingAnIncomeAmountStoresTheNewAmount() = runTest {
        val rig = rigWith(EntryWorld.income(id = 4, amount = "100", rate = "1"))

        rig.saveEntry(EntryWorld.income(id = 4, amount = "30", rate = "1"), typedRates = emptyMap())

        assertEquals(Money.of("30", "USD"), (rig.entries.findById(4) as Transaction.Income).amount)
    }
}
