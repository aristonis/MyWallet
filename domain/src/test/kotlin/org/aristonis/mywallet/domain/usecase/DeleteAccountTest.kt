package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.FakeAccountRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeLedgerQueries
import org.aristonis.mywallet.domain.usecase.fake.InUseOnlyLedgerQueries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Assumed signature: `DeleteAccount(accounts: AccountRepository, ledger: LedgerQueries)`. Whether an
 * account is in use is answered by `ledger.isAccountInUse(id)`; no entry or leg list is read.
 */
class DeleteAccountTest {

    private fun account(id: Long) = Account(
        id = id, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.of("100", "USD"),
    )

    private fun ledgerOf(vararg entries: Transaction) = FakeLedgerQueries(FakeEntryRepository(entries.toList()))

    private suspend fun assertRefused(accounts: FakeAccountRepository, ledger: org.aristonis.mywallet.domain.port.LedgerQueries, id: Long) {
        try {
            DeleteAccount(accounts, ledger).invoke(id)
            fail("expected AccountInUse")
        } catch (_: WalletException.AccountInUse) {
            // expected
        }
    }

    @Test
    fun deletesAnAccountWithNoTransactions() = runTest {
        val accounts = FakeAccountRepository(listOf(account(1)))

        DeleteAccount(accounts, ledgerOf()).invoke(1)

        assertTrue(accounts.observeAll().first().isEmpty())
    }

    @Test
    fun refusesToDeleteAnAccountThatHasTransactions_andDeletesNothing() = runTest {
        val accounts = FakeAccountRepository(listOf(account(1)))

        assertRefused(accounts, ledgerOf(EntryWorld.expense(id = 1, accountId = 1)), 1)

        assertEquals(1, accounts.observeAll().first().size)
    }

    @Test
    fun guardCoversTheTransferSourceLeg() = runTest {
        val accounts = FakeAccountRepository(listOf(account(1), account(3)))

        assertRefused(accounts, ledgerOf(EntryWorld.transfer(id = 1, sourceAccountId = 1, destAccountId = 3)), 1)

        assertEquals(2, accounts.observeAll().first().size)
    }

    @Test
    fun anAccountOnlyUsedAsATransferDestinationIsInUse() = runTest {
        val accounts = FakeAccountRepository(listOf(account(1), account(3)))

        assertRefused(accounts, ledgerOf(EntryWorld.transfer(id = 1, sourceAccountId = 1, destAccountId = 3)), 3)

        assertEquals(2, accounts.observeAll().first().size)
    }

    @Test
    fun anAccountOtherEntriesDoNotTouchIsDeleted() = runTest {
        val accounts = FakeAccountRepository(listOf(account(1), account(2)))

        DeleteAccount(accounts, ledgerOf(EntryWorld.expense(id = 1, accountId = 1))).invoke(2)

        assertEquals(listOf(1L), accounts.observeAll().first().map { it.id })
    }

    @Test
    fun inUseIsAnsweredByIsAccountInUseAndNoListIsRead() = runTest {
        val accounts = FakeAccountRepository(listOf(account(1), account(2)))
        val (ledger, asked) = InUseOnlyLedgerQueries.of(accountsInUse = setOf(1L))

        assertRefused(accounts, ledger, 1)
        DeleteAccount(accounts, ledger).invoke(2)

        assertEquals(listOf(1L, 2L), asked.toList())
        assertEquals(listOf(1L), accounts.observeAll().first().map { it.id })
    }

    @Test(expected = WalletException.AccountNotFound::class)
    fun unknownAccountFailsLoud() = runTest {
        DeleteAccount(FakeAccountRepository(), ledgerOf()).invoke(99)
    }

    @Test
    fun refusesAnAccountOnlyAnUnreadableRowPointsAt_andDeletesNothing() = runTest {
        // No readable leg is on the account; the ledger still answers "in use" because a raw copy of a
        // row the app cannot read names it.
        val accounts = FakeAccountRepository(listOf(account(1)))
        val (ledger, asked) = InUseOnlyLedgerQueries.of(accountsInUse = setOf(1L))

        assertRefused(accounts, ledger, 1)

        assertEquals(listOf(1L), asked.toList())
        assertEquals(listOf(1L), accounts.observeAll().first().map { it.id })
    }
}
