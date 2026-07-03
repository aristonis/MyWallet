package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeAccountRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeTransactionRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class DeleteAccountTest {

    private fun account(id: Long) = Account(
        id = id, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.of("100", "USD"),
    )

    private fun usd(a: String) = Money.of(a, "USD")

    @Test
    fun deletesAnAccountWithNoTransactions() = runTest {
        val accounts = FakeAccountRepository(listOf(account(1)))
        DeleteAccount(accounts, FakeTransactionRepository()).invoke(1)

        assertTrue(accounts.observeAll().first().isEmpty())
    }

    @Test
    fun refusesToDeleteAnAccountThatHasTransactions_andDeletesNothing() = runTest {
        val accounts = FakeAccountRepository(listOf(account(1)))
        val txs = FakeTransactionRepository(
            listOf(Transaction.Expense(id = 1, accountId = 1, amount = usd("10"), categoryId = 1, date = LocalDate.of(2026, 7, 1))),
        )
        try {
            DeleteAccount(accounts, txs).invoke(1)
            fail("expected AccountInUse")
        } catch (_: WalletException.AccountInUse) {
            // expected
        }
        assertEquals(1, accounts.observeAll().first().size) // nothing deleted
    }

    @Test
    fun guardCoversTheTransferSourceLeg() = runTest {
        val accounts = FakeAccountRepository(listOf(account(1), account(2)))
        val txs = FakeTransactionRepository(
            listOf(
                Transaction.Transfer(
                    id = 1, sourceAccountId = 1, destAccountId = 2,
                    sourceAmount = usd("10"), destAmount = usd("10"),
                    rateUsed = BigDecimal.ONE, date = LocalDate.of(2026, 7, 1),
                ),
            ),
        )
        try {
            DeleteAccount(accounts, txs).invoke(1)
            fail("expected AccountInUse")
        } catch (_: WalletException.AccountInUse) {
            // expected
        }
        assertEquals(2, accounts.observeAll().first().size)
    }

    @Test
    fun guardCoversTheTransferDestinationLeg() = runTest {
        // The account is only referenced as a transfer DESTINATION — the guard must still block it.
        val accounts = FakeAccountRepository(listOf(account(1), account(2)))
        val txs = FakeTransactionRepository(
            listOf(
                Transaction.Transfer(
                    id = 1, sourceAccountId = 2, destAccountId = 1,
                    sourceAmount = usd("10"), destAmount = usd("10"),
                    rateUsed = BigDecimal.ONE, date = LocalDate.of(2026, 7, 1),
                ),
            ),
        )
        try {
            DeleteAccount(accounts, txs).invoke(1)
            fail("expected AccountInUse")
        } catch (_: WalletException.AccountInUse) {
            // expected
        }
        assertEquals(2, accounts.observeAll().first().size)
    }

    @Test(expected = WalletException.AccountNotFound::class)
    fun unknownAccountFailsLoud() = runTest {
        DeleteAccount(FakeAccountRepository(), FakeTransactionRepository()).invoke(99)
    }
}
