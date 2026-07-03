package org.aristonis.mywallet.ui.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.UpdateAccount
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

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

    private fun expense(id: Long, accountId: Long) = Transaction.Expense(
        id = id, accountId = accountId, amount = Money.of("10", "USD"), categoryId = 1, date = LocalDate.of(2026, 7, 1),
    )

    private class Fixture(
        accounts: List<Account>,
        currencies: List<Currency>,
        transactions: List<Transaction> = emptyList(),
    ) {
        val accountRepo = FakeAccountRepository(accounts)
        val currencyRepo = FakeCurrencyRepository(currencies)
        val txRepo = FakeTransactionRepository(transactions)
        val viewModel = EditAccountViewModel(
            accounts = accountRepo,
            currencies = currencyRepo,
            transactions = txRepo,
            updateAccount = UpdateAccount(currencyRepo, accountRepo, txRepo),
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
        val f = Fixture(listOf(account(1)), listOf(usd), listOf(expense(1, accountId = 1)))

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
        val f = Fixture(listOf(account(1)), listOf(usd, eur), listOf(expense(1, accountId = 1)))
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
}

// In-memory port fakes shared with the other account VM tests live in AccountTestFakes.kt.
