package org.aristonis.mywallet.domain.usecase

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
import org.junit.Test
import java.time.LocalDate

class RecordExpenseTest {

    private val today = LocalDate.of(2026, 7, 2)
    private fun account(id: Long, currency: String = "USD") =
        Account(id = id, name = "Cash", typeKey = "cash", currencyCode = currency, openingBalance = Money.zero(currency))

    private val food = Category(id = 7, name = "Food", kind = CategoryKind.EXPENSE)

    @Test
    fun addsExpense_andReturnsGeneratedId() = runTest {
        val transactions = FakeTransactionRepository()
        val recordExpense = RecordExpense(
            accounts = FakeAccountRepository(listOf(account(1))),
            categories = FakeCategoryRepository(listOf(food)),
            transactions = transactions,
        )

        val id = recordExpense(accountId = 1, amount = Money.of("12.50", "USD"), categoryId = 7, date = today)

        assertEquals(1L, id)
        val saved = transactions.added.single() as Transaction.Expense
        assertEquals(Money.of("12.50", "USD"), saved.amount)
    }

    @Test(expected = WalletException.AccountNotFound::class)
    fun unknownAccount_throws() = runTest {
        RecordExpense(FakeAccountRepository(), FakeCategoryRepository(listOf(food)), FakeTransactionRepository())
            .invoke(accountId = 99, amount = Money.of("1", "USD"), categoryId = 7, date = today)
    }

    @Test(expected = WalletException.AccountArchived::class)
    fun archivedAccount_throws() = runTest {
        RecordExpense(FakeAccountRepository(listOf(account(1).copy(archived = true))), FakeCategoryRepository(listOf(food)), FakeTransactionRepository())
            .invoke(accountId = 1, amount = Money.of("12.50", "USD"), categoryId = 7, date = today)
    }

    @Test(expected = WalletException.CurrencyMismatch::class)
    fun amountInWrongCurrency_throws() = runTest {
        RecordExpense(FakeAccountRepository(listOf(account(1, "USD"))), FakeCategoryRepository(listOf(food)), FakeTransactionRepository())
            .invoke(accountId = 1, amount = Money.of("1", "EUR"), categoryId = 7, date = today)
    }
}
