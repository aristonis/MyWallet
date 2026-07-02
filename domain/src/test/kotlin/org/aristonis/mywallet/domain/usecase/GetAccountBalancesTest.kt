package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeAccountRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeTransactionRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class GetAccountBalancesTest {

    @Test
    fun combinesAccountsAndTransactionsIntoLiveBalances() = runTest {
        val accounts = FakeAccountRepository(
            listOf(Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.of("100", "USD"))),
        )
        val transactions = FakeTransactionRepository(
            listOf(Transaction.Income(id = 1, accountId = 1, amount = Money.of("25", "USD"), categoryId = 1, date = LocalDate.of(2026, 7, 2))),
        )

        val balances = GetAccountBalances(accounts, transactions).invoke().first()

        assertEquals(Money.of("125", "USD"), balances.single().balance)
    }
}
