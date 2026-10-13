package org.aristonis.mywallet.ui.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.RecordingErrorReporter
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.UnreadableRowRef
import org.aristonis.mywallet.domain.usecase.DeleteAccount
import org.aristonis.mywallet.domain.usecase.SetAccountArchived
import org.aristonis.mywallet.ui.HealthLedger
import org.aristonis.mywallet.ui.message.UiMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Account deletion stays unavailable until the ledger can be read again. */
@OptIn(ExperimentalCoroutinesApi::class)
class ManageAccountsLoadFailureTest {
    private val dispatcher = StandardTestDispatcher()
    private val ledger = HealthLedger()
    private val errors = RecordingErrorReporter()
    private val cash = Account(1, "Cash", "cash", "USD", Money.of("100", "USD"))
    private val accounts = FakeAccountRepository(listOf(cash))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = ManageAccountsViewModel(
        accounts, ledger, SetAccountArchived(accounts), DeleteAccount(accounts, ledger), ledger.health(), errors,
    )

    @Test fun unknownKindStopsAccountDeletionAndRetryReadsRepairedLedger() = runTest {
        ledger.plantUnknownKind()
        val vm = viewModel()
        runCurrent()
        assertTrue(vm.state.value.loadFailed)
        assertEquals(UiMessage.DataUnreadable, vm.state.value.loadFailure)
        assertTrue(vm.state.value.rows.isEmpty())
        vm.delete(1)
        runCurrent()
        assertEquals(cash, accounts.findById(1))

        ledger.unknownKind.value = null
        runCurrent()
        assertTrue(vm.state.value.loadFailed)
        vm.retry()
        runCurrent()
        assertFalse(vm.state.value.loadFailed)
        assertEquals(cash, vm.state.value.rows.single().account)
        vm.delete(1)
        runCurrent()
        assertTrue(vm.state.value.rows.isEmpty())
    }

    @Test fun legacyUnreadableAlsoStopsTheAccountList() = runTest {
        ledger.unknownKind.value = UnreadableRowRef(2, "LEGACY_UNREADABLE")
        val vm = viewModel()
        runCurrent()
        assertTrue(vm.state.value.loadFailed)
        assertEquals(UiMessage.DataUnreadable, vm.state.value.loadFailure)
    }

    @Test fun unexpectedReadFailureIsReportedAndCanBeRetried() = runTest {
        val failure = IllegalStateException("disk read failed")
        ledger.healthFailure = failure
        val vm = viewModel()
        runCurrent()
        assertEquals(UiMessage.Unexpected, vm.state.value.loadFailure)
        assertEquals(1, errors.reported.size)
        val reported = errors.reported.single()
        assertTrue(reported is IllegalStateException)
        assertEquals("disk read failed", reported.message)
        ledger.healthFailure = null
        vm.retry()
        runCurrent()
        assertFalse(vm.state.value.loadFailed)
        assertEquals(cash, vm.state.value.rows.single().account)
    }

    @Test fun unexpectedActionFailureIsReportedWithoutDeletingTheAccount() = runTest {
        val vm = viewModel()
        runCurrent()
        accounts.failedReadsLeft = 1
        vm.delete(1)
        runCurrent()
        assertEquals(UiMessage.Unexpected, vm.state.value.error)
        assertEquals(1, errors.reported.size)
        assertEquals(cash, accounts.findById(1))
    }

    @Test fun actionRechecksHealthBeforeDeletingAnAccount() = runTest {
        val vm = viewModel()
        runCurrent()
        ledger.healthFailure = IllegalStateException("health read failed")
        vm.delete(1)
        runCurrent()
        assertEquals(UiMessage.Unexpected, vm.state.value.error)
        assertEquals(cash, accounts.findById(1))
        assertEquals(1, errors.reported.size)
    }

    @Test fun actionCancellationIsNotReportedAsAUserError() = runTest {
        val vm = viewModel()
        runCurrent()
        ledger.healthFailure = CancellationException("read cancelled")
        vm.delete(1)
        runCurrent()
        assertEquals(null, vm.state.value.error)
        assertTrue(errors.reported.isEmpty())
        assertEquals(cash, accounts.findById(1))
    }

    @Test fun cancellationIsNotTurnedIntoAnError() = runTest {
        ledger.healthFailure = CancellationException("read cancelled")
        val vm = viewModel()
        runCurrent()
        assertFalse(vm.state.value.loadFailed)
        assertTrue(errors.reported.isEmpty())
    }
}
