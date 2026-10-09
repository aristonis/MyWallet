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
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.usecase.UpdateAccount
import org.aristonis.mywallet.ui.HealthLedger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Edit account decides whether the currency is locked from the ledger: an account with any leg is in
 * use. A ledger holding an entry the app cannot read could hide such a leg, so the screen also checks
 * the ledger's health and shows the cannot-be-read state with Retry rather than an editor that could
 * unlock the currency of an account in use.
 *
 * Assumed constructor, called by name here:
 * `EditAccountViewModel(accounts, currencies, ledger: LedgerQueries, observeLedgerHealth: ObserveLedgerHealth,
 * updateAccount, moneyParser, errors)`, which reads no entry list, and
 * `UpdateAccount(currencies, accounts, ledger: LedgerQueries)` as the account-rule step defines it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditAccountLedgerHealthTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val usd = Currency("USD", "$", 2)
    private val cash = Account(
        id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.of("100", "USD"),
    )

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
    fun `an unknown kind in ledger health shows the cannot-be-read state with Retry`() = runTest {
        ledger.plantUnknownKind(id = 7)
        val vm = editViewModel()

        vm.load(1)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.loadFailed)
        assertNull(state.loaded)
        assertFalse(state.canSubmit)
        assertEquals(7L, (errors.reported.single() as WalletException.UnreadableRow).id)
    }

    @Test
    fun `Retry reads the ledger health again and fills the form once the row is readable`() = runTest {
        ledger.plantUnknownKind()
        val vm = editViewModel()
        vm.load(1)
        advanceUntilIdle()
        assertTrue(vm.state.value.loadFailed)
        val readsBefore = ledger.healthReads

        ledger.heal()
        vm.retry()
        advanceUntilIdle()

        assertTrue("retry must read the health again", ledger.healthReads > readsBefore)
        val state = vm.state.value
        assertFalse(state.loadFailed)
        assertEquals("Cash", state.name)
        assertEquals("USD", state.selectedCurrencyCode)
    }

    @Test
    fun `an account with a leg locks its currency, answered by isAccountInUse`() = runTest {
        ledger.accountsInUse = setOf(1L)
        val vm = editViewModel()

        vm.load(1)
        advanceUntilIdle()

        assertTrue(vm.state.value.currencyLocked)
    }

    @Test
    fun `an account with no leg leaves its currency free`() = runTest {
        val vm = editViewModel()

        vm.load(1)
        advanceUntilIdle()

        assertFalse(vm.state.value.currencyLocked)
        assertFalse(vm.state.value.loadFailed)
    }
}
