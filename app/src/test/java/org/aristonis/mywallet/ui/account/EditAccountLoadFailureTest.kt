package org.aristonis.mywallet.ui.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.RecordingErrorReporter
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.usecase.UpdateAccount
import org.aristonis.mywallet.ui.HealthLedger
import org.aristonis.mywallet.ui.message.UiMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Edit account tells an unreadable stored row apart from any other failed read.
 *
 * Assumed API: `EditAccountUiState.loadFailure: UiMessage?`, null while nothing failed, and when
 * `loadFailed` is true `UiMessage.DataUnreadable` for an unreadable row and `UiMessage.Unexpected`
 * for any other failure. Only an unreadable row from the health read gets the cannot-be-read wording.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditAccountLoadFailureTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val usd = Currency("USD", "$", 2)
    private val cash = Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.of("100", "USD"))
    private val ledger = HealthLedger()
    private val errors = RecordingErrorReporter()

    private fun editViewModel(): EditAccountViewModel {
        val accounts = FakeAccountRepository(listOf(cash))
        val currencies = FakeCurrencyRepository(listOf(usd))
        return EditAccountViewModel(
            accounts = accounts,
            currencies = currencies,
            ledger = ledger,
            observeLedgerHealth = ledger.health(),
            updateAccount = UpdateAccount(currencies = currencies, accounts = accounts, ledger = ledger),
            moneyParser = MoneyParser(Locale.US),
            errors = errors,
        )
    }

    @Test
    fun `a health read that fails for another reason shows the generic failure`() = runTest {
        val failure = IllegalStateException("disk I/O error")
        ledger.healthFailure = failure
        val vm = editViewModel()

        vm.load(1)
        advanceUntilIdle()

        assertTrue(vm.state.value.loadFailed)
        assertEquals(UiMessage.Unexpected, vm.state.value.loadFailure)
        assertEquals(listOf<Throwable>(failure), errors.reported)
    }

    @Test
    fun `an unreadable row shows the cannot-be-read failure`() = runTest {
        ledger.plantUnknownKind()
        val vm = editViewModel()

        vm.load(1)
        advanceUntilIdle()

        assertTrue(vm.state.value.loadFailed)
        assertEquals(UiMessage.DataUnreadable, vm.state.value.loadFailure)
    }

    @Test
    fun `a readable account has no load failure`() = runTest {
        val vm = editViewModel()

        vm.load(1)
        advanceUntilIdle()

        assertNull(vm.state.value.loadFailure)
    }
}
