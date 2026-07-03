package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeAccountRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeTransactionRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class DeleteTransactionTest {

    private val today = LocalDate.of(2026, 7, 2)
    private fun account(id: Long, currency: String = "USD") =
        Account(id = id, name = "a$id", typeKey = "cash", currencyCode = currency, openingBalance = Money.zero(currency))

    @Test
    fun removesTheTransaction() = runTest {
        val transactions = FakeTransactionRepository(
            listOf(Transaction.Income(id = 1, accountId = 1, amount = Money.of("100", "USD"), categoryId = 5, date = today)),
        )

        DeleteTransaction(transactions).invoke(1)

        assertTrue(transactions.added.isEmpty())
    }

    @Test
    fun deletingAnIncome_restoresTheBalance() = runTest {
        // opening 0 + one 100 income = 100; after delete, back to opening
        val accounts = FakeAccountRepository(listOf(account(1)))
        val transactions = FakeTransactionRepository(
            listOf(Transaction.Income(id = 1, accountId = 1, amount = Money.of("100", "USD"), categoryId = 5, date = today)),
        )
        val balances = GetAccountBalances(accounts, transactions)

        assertEquals(Money.of("100", "USD"), balances().first().single().balance)
        DeleteTransaction(transactions).invoke(1)
        assertEquals(Money.of("0", "USD"), balances().first().single().balance)
    }

    @Test
    fun deletingATransfer_clearsBothLegs() = runTest {
        // single-row transfer: one delete removes the whole thing, no orphaned leg
        val accounts = FakeAccountRepository(listOf(account(1), account(2)))
        val transactions = FakeTransactionRepository(
            listOf(
                Transaction.Transfer(
                    id = 1, sourceAccountId = 1, destAccountId = 2,
                    sourceAmount = Money.of("40", "USD"), destAmount = Money.of("40", "USD"),
                    rateUsed = BigDecimal.ONE, date = today,
                ),
            ),
        )
        val balances = GetAccountBalances(accounts, transactions)

        val before = balances().first()
        assertEquals(Money.of("-40", "USD"), before.first { it.account.id == 1L }.balance)
        assertEquals(Money.of("40", "USD"), before.first { it.account.id == 2L }.balance)

        DeleteTransaction(transactions).invoke(1)

        val after = balances().first()
        assertEquals(Money.of("0", "USD"), after.first { it.account.id == 1L }.balance)
        assertEquals(Money.of("0", "USD"), after.first { it.account.id == 2L }.balance)
        assertTrue(transactions.added.isEmpty())
    }
}
