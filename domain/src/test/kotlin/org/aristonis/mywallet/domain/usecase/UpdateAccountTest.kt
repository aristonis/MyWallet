package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeAccountRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeCurrencyRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeLedgerQueries
import org.aristonis.mywallet.domain.usecase.fake.InUseOnlyLedgerQueries
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bar for editing an account: name/type edits persist while the fields the form never exposes
 * (archived, sortOrder) survive; a currency change is allowed only while the account has no
 * transactions (else its recorded money would silently change denomination), and an unknown target
 * currency still fails loud. Uses the in-memory domain fakes — no mocking framework.
 *
 * Assumed signature: `UpdateAccount(currencies, accounts, ledger: LedgerQueries)`. "Has transactions"
 * is `ledger.isAccountInUse(id)`, asked only when the currency changes; no list is read.
 */
class UpdateAccountTest {

    private fun account(id: Long, currency: String = "USD", archived: Boolean = false, sortOrder: Int = 0) = Account(
        id = id, name = "Cash", typeKey = "cash", currencyCode = currency,
        openingBalance = Money.of("100", currency), archived = archived, sortOrder = sortOrder,
    )

    private fun expense(id: Long, accountId: Long) = EntryWorld.expense(id = id, accountId = accountId)

    private fun transfer(id: Long, source: Long, dest: Long) =
        EntryWorld.transfer(id = id, sourceAccountId = source, destAccountId = dest)

    private fun ledgerOf(vararg entries: Transaction): LedgerQueries =
        FakeLedgerQueries(FakeEntryRepository(entries.toList()))

    private fun updateAccount(
        accounts: FakeAccountRepository,
        currencies: FakeCurrencyRepository = FakeCurrencyRepository(listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2))),
        ledger: LedgerQueries = ledgerOf(),
    ) = UpdateAccount(currencies, accounts, ledger)

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
        val ledger = ledgerOf(expense(1, accountId = 1))

        updateAccount(accounts, ledger = ledger).invoke(
            id = 1, name = "Cash", typeKey = "cash", currencyCode = "EUR", openingBalance = Money.of("100", "EUR"),
        )
    }

    @Test(expected = WalletException.AccountCurrencyLocked::class)
    fun currencyChange_accountIsTransferSource_throwsAccountCurrencyLocked() = runTest {
        val accounts = FakeAccountRepository(listOf(account(1)))
        val ledger = ledgerOf(transfer(1, source = 1, dest = 3))
        updateAccount(accounts, ledger = ledger).invoke(
            id = 1, name = "Cash", typeKey = "cash", currencyCode = "EUR", openingBalance = Money.of("100", "EUR"),
        )
    }

    @Test(expected = WalletException.AccountCurrencyLocked::class)
    fun currencyChange_accountIsTransferDestination_throwsAccountCurrencyLocked() = runTest {
        val accounts = FakeAccountRepository(listOf(account(1)))
        val ledger = ledgerOf(transfer(1, source = 3, dest = 1))
        updateAccount(accounts, ledger = ledger).invoke(
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

    @Test
    fun renamingAnAccountThatIsInUseNeedsNoUsageCheck() = runTest {
        val accounts = FakeAccountRepository(listOf(account(1)))
        val (ledger, asked) = InUseOnlyLedgerQueries.of(accountsInUse = setOf(1L))

        updateAccount(accounts, ledger = ledger).invoke(
            id = 1, name = "Wallet", typeKey = "cash", currencyCode = "USD", openingBalance = Money.of("100", "USD"),
        )

        assertEquals("Wallet", accounts.findById(1)!!.name)
        assertEquals(emptyList<Long>(), asked.toList())
    }

    @Test
    fun aCurrencyChangeAsksIsAccountInUseAndReadsNoList() = runTest {
        val accounts = FakeAccountRepository(listOf(account(1)))
        val (ledger, asked) = InUseOnlyLedgerQueries.of(accountsInUse = emptySet())

        updateAccount(accounts, ledger = ledger).invoke(
            id = 1, name = "Cash", typeKey = "cash", currencyCode = "EUR", openingBalance = Money.of("100", "EUR"),
        )

        assertEquals(listOf(1L), asked.toList())
        assertEquals("EUR", accounts.findById(1)!!.currencyCode)
    }

    @Test
    fun anAccountOnlyUsedAsATransferDestinationKeepsItsCurrency() = runTest {
        val accounts = FakeAccountRepository(listOf(account(3)))
        val (ledger, _) = InUseOnlyLedgerQueries.of(accountsInUse = setOf(3L))

        try {
            updateAccount(accounts, ledger = ledger).invoke(
                id = 3, name = "Cash", typeKey = "cash", currencyCode = "EUR", openingBalance = Money.of("100", "EUR"),
            )
            org.junit.Assert.fail("expected AccountCurrencyLocked")
        } catch (_: WalletException.AccountCurrencyLocked) {
            // expected
        }
        assertEquals("USD", accounts.findById(3)!!.currencyCode)
    }

    @Test
    fun anAccountOnlyAnUnreadableRowPointsAtKeepsItsCurrency() = runTest {
        // The ledger answers "in use" for it although no readable leg sits on it: the only reference
        // is the raw copy of a row the app cannot read.
        val accounts = FakeAccountRepository(listOf(account(1)))
        val (ledger, _) = InUseOnlyLedgerQueries.of(accountsInUse = setOf(1L))

        try {
            updateAccount(accounts, ledger = ledger).invoke(
                id = 1, name = "Cash", typeKey = "cash", currencyCode = "EUR", openingBalance = Money.of("100", "EUR"),
            )
            org.junit.Assert.fail("expected AccountCurrencyLocked")
        } catch (_: WalletException.AccountCurrencyLocked) {
            // expected
        }
        assertEquals("USD", accounts.findById(1)!!.currencyCode)
    }
}
