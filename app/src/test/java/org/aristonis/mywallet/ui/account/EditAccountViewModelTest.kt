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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * The bar for editing an account: load must faithfully re-hydrate the form from the stored row and flag
 * [currencyLocked] when the account already has transactions; a name edit must persist (proved by reading
 * the row back through the SAME fake the view model writes through) while archived/sortOrder survive; and
 * a currency change on a locked account must surface the domain error without touching the stored row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditAccountViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val usd = Currency("USD", "$", 2)
    private val eur = Currency("EUR", "€", 2)

    private fun account(id: Long, currency: String = "USD", archived: Boolean = false, sortOrder: Int = 0) = Account(
        id = id, name = "Cash", typeKey = "cash", currencyCode = currency,
        openingBalance = Money.of("100", currency), archived = archived, sortOrder = sortOrder,
    )

    private class Fixture(
        accounts: List<Account>,
        currencies: List<Currency>,
        accountsInUse: Set<Long> = emptySet(),
        val errors: RecordingErrorReporter = RecordingErrorReporter(),
    ) {
        val accountRepo = FakeAccountRepository(accounts)
        val currencyRepo = FakeCurrencyRepository(currencies)
        // An account is in use once any entry moves money on it.
        val ledger = HealthLedger().apply { this.accountsInUse = accountsInUse }
        val viewModel = EditAccountViewModel(
            accounts = accountRepo,
            currencies = currencyRepo,
            ledger = ledger,
            observeLedgerHealth = ledger.health(),
            updateAccount = UpdateAccount(currencyRepo, accountRepo, ledger),
            moneyParser = MoneyParser(Locale.US),
            errors = errors,
        )
    }

    @Test
    fun load_populatesForm_fromExistingAccount_unlockedWhenNoTransactions() = runTest {
        val f = Fixture(listOf(account(1, sortOrder = 3)), listOf(usd))

        f.viewModel.load(1)
        advanceUntilIdle()

        val s = f.viewModel.state.value
        assertEquals("Cash", s.name)
        assertEquals("cash", s.accountTypeKey)
        assertEquals("USD", s.selectedCurrencyCode)
        assertEquals("100", s.openingBalanceInput)
        assertFalse(s.currencyLocked)
    }

    @Test
    fun load_flagsCurrencyLocked_whenAccountHasTransactions() = runTest {
        val f = Fixture(listOf(account(1)), listOf(usd), accountsInUse = setOf(1))

        f.viewModel.load(1)
        advanceUntilIdle()

        assertTrue(f.viewModel.state.value.currencyLocked)
    }

    @Test
    fun editName_saves_andPreservesArchivedAndSortOrder() = runTest {
        val f = Fixture(listOf(account(1, archived = true, sortOrder = 5)), listOf(usd))
        f.viewModel.load(1)
        advanceUntilIdle()

        f.viewModel.setName("Wallet")
        f.viewModel.submit()
        advanceUntilIdle()

        val saved = f.accountRepo.upserted.single()
        assertEquals("Wallet", saved.name)
        assertTrue(saved.archived) // fields the form never exposes survive the edit
        assertEquals(5, saved.sortOrder)
        assertTrue(f.viewModel.state.value.saved)
    }

    @Test
    fun lockedCurrencyChange_surfacesDomainError_andDoesNotPersist() = runTest {
        val f = Fixture(listOf(account(1)), listOf(usd, eur), accountsInUse = setOf(1))
        f.viewModel.load(1)
        advanceUntilIdle()

        f.viewModel.selectCurrency("EUR")
        f.viewModel.submit()
        advanceUntilIdle()

        assertNotNull(f.viewModel.state.value.error)
        assertFalse(f.viewModel.state.value.saved)
        assertEquals("USD", f.accountRepo.findById(1)!!.currencyCode) // unchanged
    }

    @Test
    fun load_missingRow_failsLoud() = runTest {
        val f = Fixture(emptyList(), listOf(usd))

        f.viewModel.load(99)
        advanceUntilIdle()

        val s = f.viewModel.state.value
        assertNotNull(s.error)
        assertEquals(null, s.loaded)
    }

    @Test
    fun reloadingForAnotherAccount_showsTheNewOne_notTheStale() = runTest {
        val f = Fixture(listOf(account(1), account(2, currency = "EUR")), listOf(usd, eur))
        f.viewModel.load(1)
        advanceUntilIdle()
        f.viewModel.setName("dirty") // dirty the first form

        f.viewModel.load(2)
        advanceUntilIdle()

        val s = f.viewModel.state.value
        assertEquals(2L, s.loaded?.id)
        assertEquals("EUR", s.selectedCurrencyCode)
        assertEquals("Cash", s.name) // account 2's value, not the leftover "dirty"
    }

    @Test
    fun submit_absurdOpeningBalanceExponent_setsError_andDoesNotPersist() = runTest {
        val f = Fixture(listOf(account(1)), listOf(usd))
        f.viewModel.load(1)
        advanceUntilIdle()

        f.viewModel.setOpeningBalance("1E40") // scale -40; would OOM toPlainString at write time
        f.viewModel.submit()
        advanceUntilIdle()

        assertNotNull(f.viewModel.state.value.error)
        assertFalse(f.viewModel.state.value.saved)
    }

    @Test
    fun acknowledgeSaved_clearsTheOneShotSignal() = runTest {
        val f = Fixture(listOf(account(1)), listOf(usd))
        f.viewModel.load(1)
        advanceUntilIdle()
        f.viewModel.setName("Wallet")
        f.viewModel.submit()
        advanceUntilIdle()
        assertTrue(f.viewModel.state.value.saved)

        f.viewModel.acknowledgeSaved()
        assertFalse(f.viewModel.state.value.saved)
    }

    @Test
    fun load_whenTheReadFails_showsTheErrorInsteadOfThrowing() = runTest {
        val f = Fixture(listOf(account(1)), listOf(usd))
        f.accountRepo.failedReadsLeft = 1

        f.viewModel.load(1)
        advanceUntilIdle()

        val s = f.viewModel.state.value
        assertTrue(s.loadFailed)
        assertEquals(null, s.loaded)
        assertFalse(s.canSubmit)
        assertEquals(listOf("a stored row could not be read"), f.errors.reported.map { it.message })
    }

    @Test
    fun retry_afterAFailedRead_readsAgainAndPopulatesTheForm() = runTest {
        val f = Fixture(listOf(account(1)), listOf(usd))
        f.accountRepo.failedReadsLeft = 1
        f.viewModel.load(1)
        advanceUntilIdle()
        assertTrue(f.viewModel.state.value.loadFailed)

        f.viewModel.retry()
        advanceUntilIdle()

        val s = f.viewModel.state.value
        assertFalse(s.loadFailed)
        assertEquals("Cash", s.name)
        assertEquals("USD", s.selectedCurrencyCode)
    }

    @Test
    fun retry_beforeAnyLoad_doesNothing() = runTest {
        val f = Fixture(listOf(account(1)), listOf(usd))

        f.viewModel.retry()
        advanceUntilIdle()

        assertFalse(f.viewModel.state.value.loadFailed)
        assertEquals(null, f.viewModel.state.value.loaded)
    }
}

// In-memory port fakes shared with the other account VM tests live in AccountTestFakes.kt.
