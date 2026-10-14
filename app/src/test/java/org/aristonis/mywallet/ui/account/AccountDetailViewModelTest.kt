package org.aristonis.mywallet.ui.account

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.RecordingErrorReporter
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountEntryReader
import org.aristonis.mywallet.domain.port.AccountLeg
import org.aristonis.mywallet.domain.port.EntryKey
import org.aristonis.mywallet.domain.usecase.GetAccountBalances
import org.aristonis.mywallet.domain.usecase.GetAccountBalancesInBase
import org.aristonis.mywallet.ui.HealthLedger
import org.aristonis.mywallet.ui.navigation.ARG_ID
import org.aristonis.mywallet.ui.transaction.FakeCategoryRepository
import org.aristonis.mywallet.ui.transaction.FakeFxRepository
import org.aristonis.mywallet.ui.transaction.FakeRateRepository
import org.aristonis.mywallet.ui.transaction.FakeSettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/** Reads the account's own entries, newest first by keyset, and records what it was asked for. */
private class AccountDetailEntriesFake(
    private val byAccount: Map<Long, List<Transaction>> = emptyMap(),
) : AccountEntryReader {
    data class Read(val accountId: Long, val range: DateRange, val after: EntryKey?, val limit: Int)

    val reads = mutableListOf<Read>()

    /** When set, every read fails with it, as one over a row no recipe can read would. */
    var failure: Throwable? = null

    override fun observeAccountPage(
        accountId: Long,
        range: DateRange,
        after: EntryKey?,
        limit: Int,
    ): Flow<List<Transaction>> = flow {
        failure?.let { throw it }
        val page = byAccount[accountId].orEmpty()
            .filter { it.date in range }
            .sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.id })
            .filter { after == null || it.date < after.date || (it.date == after.date && it.id < after.id) }
            .take(limit)
        emit(page)
    }.onStart { reads += Read(accountId, range, after, limit) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccountDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val usd = Currency("USD", "$", 2)
    private val eur = Currency("EUR", "€", 2)
    private val baseDay = LocalDate.of(2026, 1, 1)

    private fun expense(id: Long, accountId: Long, day: Long = id) = Transaction.Expense(
        id = id, accountId = accountId, amount = Money.of("5.00", "USD"), categoryId = 20,
        date = baseDay.plusDays(day),
    )

    private fun checking(id: Long = 1, opening: String = "100", archived: Boolean = false) = Account(
        id = id, name = "Checking", typeKey = "cash", currencyCode = "USD",
        openingBalance = Money.of(opening, "USD"), archived = archived,
    )

    private fun card(id: Long = 1, limit: String? = "1000") = Account(
        id = id, name = "Visa", typeKey = "credit_card", currencyCode = "USD",
        openingBalance = Money.zero("USD"), creditLimit = limit?.let { Money.of(it, "USD") },
    )

    private fun TestScope.detailOf(
        accounts: List<Account>,
        entries: AccountDetailEntriesFake = AccountDetailEntriesFake(),
        legs: List<AccountLeg> = emptyList(),
        rates: List<ExchangeRate> = emptyList(),
        accountId: Long = 1,
    ): AccountDetailViewModel {
        val ledger = object : HealthLedger() {
            override fun observeAccountLegs(): Flow<List<AccountLeg>> = MutableStateFlow(legs)
        }
        val accountRepository = FakeAccountRepository(accounts)
        val currencies = FakeCurrencyRepository(listOf(usd, eur))
        val settings = FakeSettingsRepository(Settings("USD"))
        val fx = FakeFxRepository(currencies, FakeRateRepository(rates), settings)
        return AccountDetailViewModel(
            getAccountBalances = GetAccountBalances(accountRepository, ledger),
            getAccountBalancesInBase = GetAccountBalancesInBase(fx),
            accountEntries = entries,
            categories = FakeCategoryRepository(),
            currencies = currencies,
            settings = settings,
            observeLedgerHealth = ledger.health(),
            moneyFormatter = MoneyFormatter(Locale.US),
            savedStateHandle = SavedStateHandle(mapOf(ARG_ID to accountId)),
            defaultDispatcher = dispatcher,
            errors = RecordingErrorReporter(),
        ).also { vm -> backgroundScope.launch { vm.state.collect {} } }
    }

    @Test
    fun `a populated checking account shows its native balance, no credit and only its own entries newest first`() = runTest {
        val entries = AccountDetailEntriesFake(mapOf(
            1L to listOf(expense(1, 1), expense(3, 1), expense(2, 1)),
            2L to listOf(expense(9, 2)),
        ))
        val vm = detailOf(listOf(checking()), entries, legs = listOf(AccountLeg(1, Money.of("25", "USD"))))

        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.isLoading)
        assertFalse(state.notFound)
        assertNull(state.loadFailure)
        assertEquals("125.00 USD", state.header?.nativeDisplay)
        assertNull(state.header?.credit)
        assertEquals(listOf(3L, 2L, 1L), state.entries.map { it.id })
        assertTrue("only this account is asked for", entries.reads.all { it.accountId == 1L })
    }

    @Test
    fun `a credit card shows what is owed, its limit and the credit still available`() = runTest {
        val vm = detailOf(listOf(card()), legs = listOf(AccountLeg(1, Money.of("-300", "USD"))))

        advanceUntilIdle()

        val credit = vm.state.value.header?.credit
        assertNotNull(credit)
        assertEquals("300.00 USD", credit?.owedDisplay)
        assertEquals("1,000.00 USD", credit?.limitDisplay)
        assertEquals("700.00 USD", credit?.availableDisplay)
        assertFalse(credit?.overLimit ?: true)
    }

    @Test
    fun `a credit card owing more than its limit is flagged over the limit`() = runTest {
        val vm = detailOf(listOf(card()), legs = listOf(AccountLeg(1, Money.of("-1200", "USD"))))

        advanceUntilIdle()

        val credit = vm.state.value.header?.credit
        assertNotNull(credit)
        assertTrue(credit?.overLimit ?: false)
        assertEquals("1,200.00 USD", credit?.owedDisplay)
    }

    @Test
    fun `a credit card with no limit has no available credit and is never over the limit`() = runTest {
        val vm = detailOf(listOf(card(limit = null)), legs = listOf(AccountLeg(1, Money.of("-300", "USD"))))

        advanceUntilIdle()

        val credit = vm.state.value.header?.credit
        assertNotNull(credit)
        assertEquals("300.00 USD", credit?.owedDisplay)
        assertNull(credit?.limitDisplay)
        assertNull(credit?.availableDisplay)
        assertFalse(credit?.overLimit ?: true)
    }

    @Test
    fun `an account in another currency with a rate also shows its base value`() = runTest {
        val euros = Account(1, "Euro cash", "cash", "EUR", Money.of("50", "EUR"))
        val vm = detailOf(listOf(euros), rates = listOf(ExchangeRate("EUR", BigDecimal("1.10"))))

        advanceUntilIdle()

        val header = vm.state.value.header
        assertEquals("50.00 EUR", header?.nativeDisplay)
        assertEquals("55.00 USD", header?.baseDisplay)
        assertFalse(header?.missingRate ?: true)
    }

    @Test
    fun `an account in another currency with no rate says so and never shows a zero base value`() = runTest {
        val euros = Account(1, "Euro cash", "cash", "EUR", Money.of("50", "EUR"))
        val vm = detailOf(listOf(euros))

        advanceUntilIdle()

        val header = vm.state.value.header
        assertEquals("50.00 EUR", header?.nativeDisplay)
        assertTrue(header?.missingRate ?: false)
        assertNull(header?.baseDisplay)
    }

    @Test
    fun `an account in the base currency has no separate base line`() = runTest {
        val vm = detailOf(listOf(checking()))

        advanceUntilIdle()

        assertNull(vm.state.value.header?.baseDisplay)
        assertFalse(vm.state.value.header?.missingRate ?: true)
    }

    @Test
    fun `an unknown account id is not found, with no header, no loading and no balance`() = runTest {
        val vm = detailOf(listOf(checking()), accountId = 99)

        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.notFound)
        assertNull(state.header)
        assertFalse(state.isLoading)
        assertNull(state.loadFailure)
        assertTrue(state.entries.isEmpty())
    }

    @Test
    fun `an unreadable entry fails the load instead of showing the account at zero`() = runTest {
        val entries = AccountDetailEntriesFake().apply { failure = WalletException.UnreadableRow(7, "FROM_THE_FUTURE") }
        val vm = detailOf(listOf(checking()), entries)

        advanceUntilIdle()

        val state = vm.state.value
        assertNotNull(state.loadFailure)
        assertNull(state.header)
        assertFalse(state.isLoading)
        assertFalse(state.notFound)
    }

    @Test
    fun `retry shows loading again and then reads the account again`() = runTest {
        val entries = AccountDetailEntriesFake(mapOf(1L to listOf(expense(1, 1)))).apply {
            failure = WalletException.UnreadableRow(7, "FROM_THE_FUTURE")
        }
        val vm = detailOf(listOf(checking()), entries)
        val seen = mutableListOf<AccountDetailUiState>()
        backgroundScope.launch { vm.state.collect { seen += it } }
        advanceUntilIdle()
        assertNotNull(vm.state.value.loadFailure)
        val readsBefore = entries.reads.size
        seen.clear()
        entries.failure = null

        vm.retry()
        advanceUntilIdle()

        assertTrue("loading is shown while the retry reads", seen.any { it.isLoading })
        assertTrue("the account is read again", entries.reads.size > readsBefore)
        val state = vm.state.value
        assertNull(state.loadFailure)
        assertFalse(state.isLoading)
        assertEquals("100.00 USD", state.header?.nativeDisplay)
        assertEquals(listOf(1L), state.entries.map { it.id })
    }

    @Test
    fun `an empty account has its header, no entries and nothing more to load`() = runTest {
        val vm = detailOf(listOf(checking()))

        advanceUntilIdle()

        val state = vm.state.value
        assertNotNull(state.header)
        assertTrue(state.entries.isEmpty())
        assertFalse(state.hasMore)
        assertFalse(state.isLoading)
    }

    @Test
    fun `the first page holds fifty entries and says there are more`() = runTest {
        val all = (1L..60L).map { expense(it, 1) }
        val entries = AccountDetailEntriesFake(mapOf(1L to all))
        val vm = detailOf(listOf(checking()), entries)

        advanceUntilIdle()

        val state = vm.state.value
        assertEquals((60L downTo 11L).toList(), state.entries.map { it.id })
        assertTrue(state.hasMore)
        assertFalse(state.isLoadingMore)
        assertNull(entries.reads.first().after)
    }

    @Test
    fun `loading more appends the next page after the last key without reading earlier pages again`() = runTest {
        val all = (1L..60L).map { expense(it, 1) }
        val entries = AccountDetailEntriesFake(mapOf(1L to all))
        val vm = detailOf(listOf(checking()), entries)
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals((60L downTo 1L).toList(), state.entries.map { it.id })
        assertFalse(state.hasMore)
        assertFalse(state.isLoadingMore)
        val nextRead = entries.reads.first { it.after != null }
        assertEquals(EntryKey(baseDay.plusDays(11), 11), nextRead.after)
        assertEquals("the first page is read only once", 1, entries.reads.count { it.after == null })
    }

    @Test
    fun `an archived account still opens and says it is archived`() = runTest {
        val vm = detailOf(listOf(checking(archived = true)))

        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.notFound)
        assertEquals(true, state.header?.archived)
        assertEquals(1L, state.header?.id)
    }
}
