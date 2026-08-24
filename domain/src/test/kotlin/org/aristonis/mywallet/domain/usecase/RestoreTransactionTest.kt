package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeAccountRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeCategoryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeTransactionRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class RestoreTransactionTest {

    private val today = LocalDate.of(2026, 7, 2)
    private fun account(id: Long, currency: String = "USD") =
        Account(id = id, name = "a$id", typeKey = "cash", currencyCode = currency, openingBalance = Money.zero(currency))

    private val income = Transaction.Income(
        id = 1, accountId = 1, amount = Money.of("100", "USD"), categoryId = 5, date = today,
    )

    /** The category the undone row names — still present unless a test deliberately removes it. */
    private fun categories() = FakeCategoryRepository(
        listOf(Category(id = 5, name = "Salary", kind = CategoryKind.INCOME)),
    )

    @Test
    fun reAddsTheTransaction() = runTest {
        val transactions = FakeTransactionRepository() // empty: the row was just deleted
        RestoreTransaction(transactions, categories()).invoke(income)
        assertEquals(income, transactions.added.single())
    }

    @Test
    fun restoringAnIncome_bringsBackTheBalanceEffect() = runTest {
        // Acceptance spine: delete drops the income's balance effect, restore returns it.
        val accounts = FakeAccountRepository(listOf(account(1)))
        val transactions = FakeTransactionRepository(listOf(income))
        val balances = GetAccountBalances(accounts, transactions)
        val restore = RestoreTransaction(transactions, categories())

        assertEquals(Money.of("100", "USD"), balances().first().single().balance)
        DeleteTransaction(transactions).invoke(1)
        assertEquals(Money.of("0", "USD"), balances().first().single().balance)
        restore(income)
        assertEquals(Money.of("100", "USD"), balances().first().single().balance)
    }

    @Test
    fun restoringATransfer_returnsBothLegs() = runTest {
        // A transfer is a single row, so restore brings both legs back together.
        val accounts = FakeAccountRepository(listOf(account(1), account(2)))
        val transfer = Transaction.Transfer(
            id = 1, sourceAccountId = 1, destAccountId = 2,
            sourceAmount = Money.of("40", "USD"), destAmount = Money.of("40", "USD"),
            rateUsed = BigDecimal.ONE, date = today,
        )
        val transactions = FakeTransactionRepository(listOf(transfer))
        val balances = GetAccountBalances(accounts, transactions)

        DeleteTransaction(transactions).invoke(1)
        assertEquals(Money.of("0", "USD"), balances().first().first { it.account.id == 1L }.balance)

        RestoreTransaction(transactions, categories()).invoke(transfer)

        val after = balances().first()
        assertEquals(Money.of("-40", "USD"), after.first { it.account.id == 1L }.balance)
        assertEquals(Money.of("40", "USD"), after.first { it.account.id == 2L }.balance)
    }

    @Test
    fun refusesToBringBackARowWhoseCategoryWasDeletedMeanwhile() = runTest {
        // The undo window stays open while the user can reach the category screen, so the category
        // can be gone by the time undo is tapped. Re-inserting blind would resurrect a transaction
        // filed under a category that no longer exists, silently, since that column has no key.
        val transactions = FakeTransactionRepository()
        val emptyCategories = FakeCategoryRepository()

        try {
            RestoreTransaction(transactions, emptyCategories).invoke(income)
            fail("expected CategoryNotFound")
        } catch (_: WalletException.CategoryNotFound) {
            // expected
        }
        assertTrue("nothing may be written", transactions.added.isEmpty())
    }

    @Test
    fun refusesToBringBackARowWhoseCategoryBecameASubCategory() = runTest {
        val transactions = FakeTransactionRepository()
        val reparented = FakeCategoryRepository(
            listOf(
                Category(id = 4, name = "Work", kind = CategoryKind.INCOME),
                Category(id = 5, name = "Salary", kind = CategoryKind.INCOME, parentId = 4),
            ),
        )

        try {
            RestoreTransaction(transactions, reparented).invoke(income)
            fail("expected CategoryNotTopLevel")
        } catch (_: WalletException.CategoryNotTopLevel) {
            // expected
        }
        assertTrue("nothing may be written", transactions.added.isEmpty())
    }

    @Test
    fun aTransferIsRestoredEvenWithNoCategoriesAtAll() = runTest {
        // Transfers are never categorized, so the category check must not stand in their way.
        val transactions = FakeTransactionRepository()
        val transfer = Transaction.Transfer(
            id = 9, sourceAccountId = 1, destAccountId = 2,
            sourceAmount = Money.of("40", "USD"), destAmount = Money.of("40", "USD"),
            rateUsed = BigDecimal.ONE, date = today,
        )

        RestoreTransaction(transactions, FakeCategoryRepository()).invoke(transfer)

        assertEquals(transfer, transactions.added.single())
    }
}
