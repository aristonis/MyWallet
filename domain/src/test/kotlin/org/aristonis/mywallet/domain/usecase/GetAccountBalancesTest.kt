package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeAccountRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeLedgerQueries
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Assumed constructor: `GetAccountBalances(accounts: AccountRepository, ledger: LedgerQueries)`.
 * Balances come from account legs, so nothing reads entries.
 */
class GetAccountBalancesTest {

    private val day = LocalDate.of(2026, 7, 2)
    private val cash = Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.of("100", "USD"))

    @Test
    fun combinesAccountsAndAccountLegsIntoLiveBalances() = runTest {
        val entries = FakeEntryRepository(
            listOf(Transaction.Income(id = 1, accountId = 1, amount = Money.of("25", "USD"), categoryId = 1, date = day)),
        )

        val balances = GetAccountBalances(FakeAccountRepository(listOf(cash)), FakeLedgerQueries(entries))().first()

        assertEquals(Money.of("125", "USD"), balances.single().balance)
    }

    @Test
    fun aNewEntryChangesTheNextBalanceRead() = runTest {
        val entries = FakeEntryRepository()
        val balances = GetAccountBalances(FakeAccountRepository(listOf(cash)), FakeLedgerQueries(entries))
        assertEquals(Money.of("100", "USD"), balances().first().single().balance)

        entries.insert(Transaction.Expense(accountId = 1, amount = Money.of("40", "USD"), categoryId = 2, date = day))

        assertEquals(Money.of("60", "USD"), balances().first().single().balance)
    }

    @Test
    fun anAccountWithNoLegsKeepsItsOpeningBalance() = runTest {
        val balances = GetAccountBalances(FakeAccountRepository(listOf(cash)), FakeLedgerQueries(FakeEntryRepository()))().first()

        assertEquals(Money.of("100", "USD"), balances.single().balance)
    }
}
