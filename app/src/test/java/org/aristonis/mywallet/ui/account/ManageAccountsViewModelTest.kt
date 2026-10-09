package org.aristonis.mywallet.ui.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.usecase.DeleteAccount
import org.aristonis.mywallet.domain.usecase.SetAccountArchived
import org.aristonis.mywallet.ui.HealthLedger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManageAccountsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun account(id: Long, archived: Boolean = false) = Account(
        id = id, name = "Acc$id", typeKey = "cash", currencyCode = "USD",
        openingBalance = Money.of("100", "USD"), archived = archived,
    )

    /** The list's flag and the delete guard both ask this one ledger, as they do in the app. */
    private fun buildVm(
        accounts: List<Account>,
        accountsInUse: Set<Long> = emptySet(),
        ledger: HealthLedger = HealthLedger().apply { this.accountsInUse = accountsInUse },
    ): ManageAccountsViewModel {
        val accountRepo = FakeAccountRepository(accounts)
        return ManageAccountsViewModel(accountRepo, ledger, SetAccountArchived(accountRepo), DeleteAccount(accountRepo, ledger))
    }

    private fun ManageAccountsViewModel.rowsById() = state.value.rows.associateBy { it.account.id }

    @Test
    fun listsAllAccounts_withHasTransactionsFlag() = runTest {
        val vm = buildVm(accounts = listOf(account(1), account(2, archived = true)), accountsInUse = setOf(1))
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        val rows = vm.rowsById()
        assertEquals(2, rows.size) // archived accounts ARE listed here (unlike Home)
        assertTrue(rows.getValue(1).hasTransactions)
        assertFalse(rows.getValue(2).hasTransactions)
    }

    @Test
    fun theFlagFollowsTheLedgerAsAnAccountGetsItsFirstEntry() = runTest {
        val ledger = HealthLedger()
        val vm = buildVm(accounts = listOf(account(1)), ledger = ledger)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertFalse(vm.rowsById().getValue(1).hasTransactions)

        ledger.accountsInUse = setOf(1)
        advanceUntilIdle()

        assertTrue(vm.rowsById().getValue(1).hasTransactions)
    }

    @Test
    fun archive_thenUnarchive_flipsTheFlag() = runTest {
        val vm = buildVm(accounts = listOf(account(1)))
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.archive(1); advanceUntilIdle()
        assertTrue(vm.rowsById().getValue(1).account.archived)

        vm.unarchive(1); advanceUntilIdle()
        assertFalse(vm.rowsById().getValue(1).account.archived)
    }

    @Test
    fun delete_removesAnAccountWithNoTransactions() = runTest {
        val vm = buildVm(accounts = listOf(account(1)))
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.delete(1); advanceUntilIdle()
        assertTrue(vm.state.value.rows.isEmpty())
        assertNull(vm.state.value.error)
    }

    @Test
    fun delete_blockedForAccountWithTransactions_setsError_andKeepsAccount() = runTest {
        val vm = buildVm(accounts = listOf(account(1)), accountsInUse = setOf(1))
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.delete(1); advanceUntilIdle()
        assertNotNull(vm.state.value.error) // fail-loud: "archive it instead"
        assertEquals(1, vm.state.value.rows.size) // nothing deleted
    }
}

// In-memory port fakes shared with the other account VM tests live in AccountTestFakes.kt.
