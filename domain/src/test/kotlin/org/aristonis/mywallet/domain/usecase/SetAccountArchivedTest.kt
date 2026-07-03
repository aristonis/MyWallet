package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.usecase.fake.FakeAccountRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetAccountArchivedTest {

    private fun account(id: Long, archived: Boolean = false) = Account(
        id = id, name = "Cash", typeKey = "cash", currencyCode = "USD",
        openingBalance = Money.of("100", "USD"), archived = archived,
    )

    @Test
    fun archivesAnAccount() = runTest {
        val accounts = FakeAccountRepository(listOf(account(1, archived = false)))
        SetAccountArchived(accounts).invoke(1, archived = true)

        assertTrue(accounts.observeAll().first().single { it.id == 1L }.archived)
    }

    @Test
    fun unarchivesAnAccount() = runTest {
        val accounts = FakeAccountRepository(listOf(account(1, archived = true)))
        SetAccountArchived(accounts).invoke(1, archived = false)

        assertEquals(false, accounts.observeAll().first().single { it.id == 1L }.archived)
    }

    @Test(expected = WalletException.AccountNotFound::class)
    fun unknownAccountFailsLoud() = runTest {
        SetAccountArchived(FakeAccountRepository()).invoke(99, archived = true)
    }
}
