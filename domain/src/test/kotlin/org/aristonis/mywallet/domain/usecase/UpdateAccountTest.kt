package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeAccountRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeCurrencyRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeTransactionRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The bar for editing an account: name/type edits persist while the fields the form never exposes
 * (archived, sortOrder) survive; a currency change is allowed only while the account has no
 * transactions (else its recorded money would silently change denomination), and an unknown target
 * currency still fails loud. Uses the in-memory domain fakes — no mocking framework.
 */
class UpdateAccountTest {

    private fun account(id: Long, currency: String = "USD", archived: Boolean = false, sortOrder: Int = 0) = Account(
        id = id, name = "Cash", typeKey = "cash", currencyCode = currency,
        openingBalance = Money.of("100", currency), archived = archived, sortOrder = sortOrder,
    )

    private fun expense(id: Long, accountId: Long) = Transaction.Expense(
        id = id, accountId = accountId, amount = Money.of("10", "USD"), categoryId = 1, date = LocalDate.of(2026, 7, 1),
    )

    private fun transfer(id: Long, source: Long, dest: Long) = Transaction.Transfer(
        id = id, sourceAccountId = source, destAccountId = dest,
        sourceAmount = Money.of("10", "USD"), destAmount = Money.of("10", "USD"),
        rateUsed = BigDecimal.ONE, date = LocalDate.of(2026, 7, 1),
    )

    private fun updateAccount(
        accounts: FakeAccountRepository,
        currencies: FakeCurrencyRepository = FakeCurrencyRepository(listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2))),
        transactions: FakeTransactionRepository = FakeTransactionRepository(),
    ) = UpdateAccount(currencies, accounts, transactions)

    @Test
    fun editsNameAndType_persists_andPreservesArchivedAndSortOrder() = runTest {
        val accounts = FakeAccountRepository(listOf(account(1, archived = true, sortOrder = 7)))

        updateAccount(accounts).invoke(
            id = 1, name = "Wallet", typeKey = "card", currencyCode = "USD", openingBalance = Money.of("100", "USD"),
        )

        val saved = accounts.findById(1)!!
        assertEquals("Wallet", saved.name)
        assertEquals("card", saved.typeKey)
        assertEquals("USD", saved.currencyCode)
        assertEquals(true, saved.archived) // fields the form never touches survive the edit
        assertEquals(7, saved.sortOrder)
    }

    @Test
    fun currencyChange_withNoTransactions_isAllowed() = runTest {
        val accounts = FakeAccountRepository(listOf(account(1)))

        updateAccount(accounts).invoke(
            id = 1, name = "Cash", typeKey = "cash", currencyCode = "EUR", openingBalance = Money.of("100", "EUR"),
        )

        val saved = accounts.findById(1)!!
        assertEquals("EUR", saved.currencyCode)
        assertEquals(Money.of("100", "EUR"), saved.openingBalance)
    }

    @Test(expected = WalletException.AccountCurrencyLocked::class)
    fun currencyChange_withTransactions_throwsAccountCurrencyLocked() = runTest {
        val accounts = FakeAccountRepository(listOf(account(1)))
        val transactions = FakeTransactionRepository(listOf(expense(1, accountId = 1)))

        updateAccount(accounts, transactions = transactions).invoke(
            id = 1, name = "Cash", typeKey = "cash", currencyCode = "EUR", openingBalance = Money.of("100", "EUR"),
        )
    }

    @Test(expected = WalletException.AccountCurrencyLocked::class)
    fun currencyChange_accountIsTransferSource_throwsAccountCurrencyLocked() = runTest {
        val accounts = FakeAccountRepository(listOf(account(1)))
        val transactions = FakeTransactionRepository(listOf(transfer(1, source = 1, dest = 2)))
        updateAccount(accounts, transactions = transactions).invoke(
            id = 1, name = "Cash", typeKey = "cash", currencyCode = "EUR", openingBalance = Money.of("100", "EUR"),
        )
    }

    @Test(expected = WalletException.AccountCurrencyLocked::class)
    fun currencyChange_accountIsTransferDestination_throwsAccountCurrencyLocked() = runTest {
        val accounts = FakeAccountRepository(listOf(account(1)))
        val transactions = FakeTransactionRepository(listOf(transfer(1, source = 2, dest = 1)))
        updateAccount(accounts, transactions = transactions).invoke(
            id = 1, name = "Cash", typeKey = "cash", currencyCode = "EUR", openingBalance = Money.of("100", "EUR"),
        )
    }

    @Test(expected = WalletException.AccountNotFound::class)
    fun unknownId_throwsAccountNotFound() = runTest {
        updateAccount(FakeAccountRepository()).invoke(
            id = 99, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.of("100", "USD"),
        )
    }

    @Test(expected = WalletException.CurrencyNotFound::class)
    fun changingToUnknownCurrency_throwsCurrencyNotFound() = runTest {
        val accounts = FakeAccountRepository(listOf(account(1)))

        updateAccount(accounts).invoke(
            id = 1, name = "Cash", typeKey = "cash", currencyCode = "XXX", openingBalance = Money.of("100", "XXX"),
        )
    }
}
