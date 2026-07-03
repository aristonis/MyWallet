package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeAccountRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeCategoryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeCurrencyRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeTransactionRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class UpdateTransactionTest {

    private val today = LocalDate.of(2026, 7, 2)
    private fun account(id: Long, currency: String = "USD") =
        Account(id = id, name = "a$id", typeKey = "cash", currencyCode = currency, openingBalance = Money.zero(currency))
    private val salary = Category(id = 5, name = "Salary", kind = CategoryKind.INCOME)
    private val food = Category(id = 6, name = "Food", kind = CategoryKind.EXPENSE)

    private fun update(
        accounts: List<Account>,
        transactions: FakeTransactionRepository,
        categories: List<Category> = listOf(salary, food),
        currencies: List<Currency> = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)),
    ) = UpdateTransaction(
        accounts = FakeAccountRepository(accounts),
        categories = FakeCategoryRepository(categories),
        currencies = FakeCurrencyRepository(currencies),
        transactions = transactions,
    )

    // --- income / expense: same guards as recording, id preserved ---

    @Test
    fun editsIncomeAmount_andBalanceReflectsIt() = runTest {
        val income = Transaction.Income(id = 1, accountId = 1, amount = Money.of("100", "USD"), categoryId = 5, date = today)
        val transactions = FakeTransactionRepository(listOf(income))
        val balances = GetAccountBalances(FakeAccountRepository(listOf(account(1))), transactions)

        update(listOf(account(1)), transactions).invoke(income.copy(amount = Money.of("30", "USD")))

        assertEquals(Money.of("30", "USD"), balances().first().single().balance)
        assertEquals(Money.of("30", "USD"), (transactions.added.single() as Transaction.Income).amount)
    }

    @Test(expected = WalletException.AccountNotFound::class)
    fun income_unknownAccount_throws() = runTest {
        val income = Transaction.Income(id = 1, accountId = 99, amount = Money.of("1", "USD"), categoryId = 5, date = today)
        update(listOf(account(1)), FakeTransactionRepository(listOf(income))).invoke(income)
    }

    @Test(expected = WalletException.AccountArchived::class)
    fun income_archivedAccount_throws() = runTest {
        val income = Transaction.Income(id = 1, accountId = 1, amount = Money.of("1", "USD"), categoryId = 5, date = today)
        update(listOf(account(1).copy(archived = true)), FakeTransactionRepository(listOf(income))).invoke(income)
    }

    @Test(expected = WalletException.CurrencyMismatch::class)
    fun income_currencyMismatch_throws() = runTest {
        val income = Transaction.Income(id = 1, accountId = 1, amount = Money.of("1", "EUR"), categoryId = 5, date = today)
        update(listOf(account(1, "USD")), FakeTransactionRepository(listOf(income))).invoke(income)
    }

    @Test(expected = WalletException.CategoryNotFound::class)
    fun income_unknownCategory_throws() = runTest {
        val income = Transaction.Income(id = 1, accountId = 1, amount = Money.of("1", "USD"), categoryId = 404, date = today)
        update(listOf(account(1)), FakeTransactionRepository(listOf(income))).invoke(income)
    }

    @Test
    fun editsExpenseAmount_andBalanceReflectsIt() = runTest {
        val acct = account(1).copy(openingBalance = Money.of("100", "USD"))
        val expense = Transaction.Expense(id = 1, accountId = 1, amount = Money.of("40", "USD"), categoryId = 6, date = today)
        val transactions = FakeTransactionRepository(listOf(expense))
        val balances = GetAccountBalances(FakeAccountRepository(listOf(acct)), transactions)

        assertEquals(Money.of("60", "USD"), balances().first().single().balance) // 100 - 40
        update(listOf(acct), transactions).invoke(expense.copy(amount = Money.of("25", "USD")))
        assertEquals(Money.of("75", "USD"), balances().first().single().balance) // 100 - 25
    }

    // --- transfer: preserve the original stored rate (option B) ---

    @Test
    fun editsSameCurrencyTransfer_destTracksSource() = runTest {
        val transfer = Transaction.Transfer(
            id = 1, sourceAccountId = 1, destAccountId = 2,
            sourceAmount = Money.of("40", "USD"), destAmount = Money.of("40", "USD"),
            rateUsed = BigDecimal.ONE, date = today,
        )
        val transactions = FakeTransactionRepository(listOf(transfer))

        update(listOf(account(1), account(2)), transactions).invoke(transfer.copy(sourceAmount = Money.of("25", "USD")))

        val saved = transactions.added.single() as Transaction.Transfer
        assertEquals(Money.of("25", "USD"), saved.sourceAmount)
        assertEquals(Money.of("25", "USD"), saved.destAmount)
    }

    @Test
    fun editsCrossCurrencyTransferAmount_recomputesDestAtOriginalRate() = runTest {
        // stored: 10 USD -> 9.00 EUR at rate 0.90. Edit source to 20 USD -> 18.00 EUR at the SAME rate.
        val transfer = Transaction.Transfer(
            id = 1, sourceAccountId = 1, destAccountId = 2,
            sourceAmount = Money.of("10", "USD"), destAmount = Money.of("9.00", "EUR"),
            rateUsed = BigDecimal("0.90"), date = today,
        )
        val transactions = FakeTransactionRepository(listOf(transfer))

        update(listOf(account(1, "USD"), account(2, "EUR")), transactions).invoke(transfer.copy(sourceAmount = Money.of("20", "USD")))

        val saved = transactions.added.single() as Transaction.Transfer
        assertEquals(Money.of("20", "USD"), saved.sourceAmount)
        assertEquals(Money.of("18.00", "EUR"), saved.destAmount)
        assertEquals(0, BigDecimal("0.90").compareTo(saved.rateUsed)) // rate preserved, not re-fetched
    }

    @Test
    fun editingOnlyTheNote_doesNotMoveMoney() = runTest {
        val transfer = Transaction.Transfer(
            id = 1, sourceAccountId = 1, destAccountId = 2,
            sourceAmount = Money.of("10", "USD"), destAmount = Money.of("9.00", "EUR"),
            rateUsed = BigDecimal("0.90"), date = today,
        )
        val transactions = FakeTransactionRepository(listOf(transfer))

        update(listOf(account(1, "USD"), account(2, "EUR")), transactions).invoke(transfer.copy(note = "lunch"))

        val saved = transactions.added.single() as Transaction.Transfer
        assertEquals(Money.of("10", "USD"), saved.sourceAmount)
        assertEquals(Money.of("9.00", "EUR"), saved.destAmount)
        assertEquals("lunch", saved.note)
    }

    @Test(expected = WalletException.AccountArchived::class)
    fun transfer_archivedDestination_throws() = runTest {
        val transfer = Transaction.Transfer(
            id = 1, sourceAccountId = 1, destAccountId = 2,
            sourceAmount = Money.of("10", "USD"), destAmount = Money.of("10", "USD"),
            rateUsed = BigDecimal.ONE, date = today,
        )
        update(listOf(account(1), account(2).copy(archived = true)), FakeTransactionRepository(listOf(transfer))).invoke(transfer)
    }

    @Test(expected = WalletException.AccountNotFound::class)
    fun transfer_unknownSource_throws() = runTest {
        val transfer = Transaction.Transfer(
            id = 1, sourceAccountId = 99, destAccountId = 2,
            sourceAmount = Money.of("10", "USD"), destAmount = Money.of("10", "USD"),
            rateUsed = BigDecimal.ONE, date = today,
        )
        update(listOf(account(2)), FakeTransactionRepository(listOf(transfer))).invoke(transfer)
    }

    @Test(expected = WalletException.AccountArchived::class)
    fun transfer_archivedSource_throws() = runTest {
        val transfer = Transaction.Transfer(
            id = 1, sourceAccountId = 1, destAccountId = 2,
            sourceAmount = Money.of("10", "USD"), destAmount = Money.of("10", "USD"),
            rateUsed = BigDecimal.ONE, date = today,
        )
        update(listOf(account(1).copy(archived = true), account(2)), FakeTransactionRepository(listOf(transfer))).invoke(transfer)
    }

    @Test(expected = WalletException.AccountNotFound::class)
    fun transfer_unknownDestination_throws() = runTest {
        val transfer = Transaction.Transfer(
            id = 1, sourceAccountId = 1, destAccountId = 99,
            sourceAmount = Money.of("10", "USD"), destAmount = Money.of("10", "USD"),
            rateUsed = BigDecimal.ONE, date = today,
        )
        update(listOf(account(1)), FakeTransactionRepository(listOf(transfer))).invoke(transfer)
    }

    // --- edit integrity (fail-loud) ---

    @Test(expected = WalletException.TransactionNotFound::class)
    fun editingAMissingTransaction_throws() = runTest {
        // the row was deleted since the editor opened — the save must fail loud, not silently no-op
        val income = Transaction.Income(id = 7, accountId = 1, amount = Money.of("5", "USD"), categoryId = 5, date = today)
        update(listOf(account(1)), FakeTransactionRepository()).invoke(income)
    }

    @Test(expected = WalletException.TransferCurrencyPairChanged::class)
    fun editingATransferToADifferentCurrencyPair_throws() = runTest {
        // stored USD->EUR at 0.90; moving the destination to a GBP account changes the pair, so its stored rate no longer applies
        val transfer = Transaction.Transfer(
            id = 1, sourceAccountId = 1, destAccountId = 2,
            sourceAmount = Money.of("10", "USD"), destAmount = Money.of("9.00", "EUR"),
            rateUsed = BigDecimal("0.90"), date = today,
        )
        update(
            accounts = listOf(account(1, "USD"), account(2, "EUR"), account(3, "GBP")),
            transactions = FakeTransactionRepository(listOf(transfer)),
            currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2), Currency("GBP", "£", 2)),
        ).invoke(transfer.copy(destAccountId = 3))
    }

    @Test
    fun editingATransferToASameCurrencyAccount_isAllowed() = runTest {
        // moving the source to another USD account keeps the USD->EUR pair, so the stored rate still applies
        val transfer = Transaction.Transfer(
            id = 1, sourceAccountId = 1, destAccountId = 2,
            sourceAmount = Money.of("10", "USD"), destAmount = Money.of("9.00", "EUR"),
            rateUsed = BigDecimal("0.90"), date = today,
        )
        val transactions = FakeTransactionRepository(listOf(transfer))

        update(listOf(account(1, "USD"), account(2, "EUR"), account(3, "USD")), transactions).invoke(transfer.copy(sourceAccountId = 3))

        val saved = transactions.added.single() as Transaction.Transfer
        assertEquals(3L, saved.sourceAccountId)
        assertEquals(Money.of("9.00", "EUR"), saved.destAmount)
    }
}
