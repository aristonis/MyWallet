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

class RecordIncomeTest {

    private val today = LocalDate.of(2026, 7, 2)
    private fun account(id: Long, currency: String = "USD") =
        Account(id = id, name = "Cash", typeKey = "cash", currencyCode = currency, openingBalance = Money.zero(currency))

    private val salary = Category(id = 5, name = "Salary", kind = CategoryKind.INCOME)

    @Test
    fun addsIncome_andReturnsGeneratedId() = runTest {
        val transactions = FakeTransactionRepository()
        val recordIncome = RecordIncome(
            accounts = FakeAccountRepository(listOf(account(1))),
            categories = FakeCategoryRepository(listOf(salary)),
            transactions = transactions,
        )

        val id = recordIncome(accountId = 1, amount = Money.of("100", "USD"), categoryId = 5, date = today)

        assertEquals(1L, id)
        val saved = transactions.added.single() as Transaction.Income
        assertEquals(Money.of("100", "USD"), saved.amount)
        assertEquals(5L, saved.categoryId)
    }

    @Test(expected = WalletException.AccountNotFound::class)
    fun unknownAccount_throws() = runTest {
        RecordIncome(FakeAccountRepository(), FakeCategoryRepository(listOf(salary)), FakeTransactionRepository())
            .invoke(accountId = 99, amount = Money.of("1", "USD"), categoryId = 5, date = today)
    }

    @Test(expected = WalletException.AccountArchived::class)
    fun archivedAccount_throws() = runTest {
        RecordIncome(FakeAccountRepository(listOf(account(1).copy(archived = true))), FakeCategoryRepository(listOf(salary)), FakeTransactionRepository())
            .invoke(accountId = 1, amount = Money.of("100", "USD"), categoryId = 5, date = today)
    }

    @Test(expected = WalletException.CurrencyMismatch::class)
    fun amountInWrongCurrency_throws() = runTest {
        RecordIncome(FakeAccountRepository(listOf(account(1, "USD"))), FakeCategoryRepository(listOf(salary)), FakeTransactionRepository())
            .invoke(accountId = 1, amount = Money.of("1", "EUR"), categoryId = 5, date = today)
    }

    @Test(expected = WalletException.CategoryNotFound::class)
    fun unknownCategory_throws() = runTest {
        RecordIncome(FakeAccountRepository(listOf(account(1))), FakeCategoryRepository(), FakeTransactionRepository())
            .invoke(accountId = 1, amount = Money.of("1", "USD"), categoryId = 404, date = today)
    }
}
