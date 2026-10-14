package org.aristonis.mywallet.ui.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.RecordingErrorReporter
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.AccountLeg
import org.aristonis.mywallet.domain.usecase.DeleteAccount
import org.aristonis.mywallet.domain.usecase.SetAccountArchived
import org.aristonis.mywallet.ui.HealthLedger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class ManageAccountsBalanceTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun activeAndArchivedRowsShowLiveBalancesInTheirOwnCurrencies() = runTest {
        val accounts = FakeAccountRepository(listOf(
            Account(1, "Cash", "cash", "USD", Money.of("100", "USD")),
            Account(2, "Old savings", "cash", "EUR", Money.of("50", "EUR"), archived = true),
        ))
        val legs = MutableStateFlow(listOf(
            AccountLeg(1, Money.of("25", "USD")),
            AccountLeg(2, Money.of("-10", "EUR")),
        ))
        val ledger = object : HealthLedger() {
            override fun observeAccountLegs(): Flow<List<AccountLeg>> = legs
        }
        val vm = ManageAccountsViewModel(
            accounts, ledger,
            FakeCurrencyRepository(listOf(Currency("USD", "$", 2), Currency("EUR", "€", 3))),
            MoneyFormatter(Locale.US), dispatcher, SetAccountArchived(accounts), DeleteAccount(accounts, ledger),
            ledger.health(), RecordingErrorReporter(),
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        val initial = vm.state.value.rows.associateBy { it.account.id }
        assertEquals(Money.of("125", "USD"), initial.getValue(1).balance)
        assertEquals(Money.of("40", "EUR"), initial.getValue(2).balance)
        assertEquals("125.00 USD", initial.getValue(1).balanceDisplay)
        assertEquals("40.000 EUR", initial.getValue(2).balanceDisplay)

        legs.value = legs.value + AccountLeg(1, Money.of("-5", "USD"))
        advanceUntilIdle()

        assertEquals(Money.of("120", "USD"), vm.state.value.rows.first { it.account.id == 1L }.balance)
    }
}
