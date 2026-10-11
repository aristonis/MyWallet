package org.aristonis.mywallet.ui.rates

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.RecordingErrorReporter
import org.aristonis.mywallet.domain.usecase.ObserveLedgerHealth
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.HomeSummary
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.DebtLeg
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.usecase.ComputeHomeSummary
import org.aristonis.mywallet.domain.usecase.GetAccountBalances
import org.aristonis.mywallet.domain.usecase.SetExchangeRate
import org.aristonis.mywallet.ui.transaction.DirectTransactionRunner
import org.aristonis.mywallet.ui.transaction.FakeCurrencyRepository
import org.aristonis.mywallet.ui.transaction.FakeEntryRepository
import org.aristonis.mywallet.ui.transaction.FakeFxRepository
import org.aristonis.mywallet.ui.transaction.FakeLedgerQueries
import org.aristonis.mywallet.ui.transaction.FakeRateRepository
import org.aristonis.mywallet.ui.transaction.FakeSettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class RatesDebtRecoveryTest {
    private val work = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(work)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun savingADebtOnlyCurrencyRateRecoversTheSameHomeSummarySubscriber() = runTest(work) {
        val rig = Rig(listOf(account(1, "USD", "700")), listOf(leg(1, "EUR", "100")))
        val vm = rig.vm()
        try {
            var summary: HomeSummary? = null
            backgroundScope.launch { rig.summary(rig.balances()).collect { summary = it } }
            runCurrent()
            assertEquals(HomeSummary.MissingRate("EUR"), summary)
            assertEquals(listOf("EUR"), vm.state.value.rows.map { it.currencyCode })
            vm.setRateInput("EUR", "2")
            vm.submit("EUR")
            runCurrent()
            val amounts = summary as HomeSummary.Amounts
            assertEquals(Money.of("200", "USD"), amounts.iOwe)
            assertEquals(Money.of("500", "USD"), amounts.balance)
            assertEquals("2", vm.state.value.rows.single().input)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun anArchivedOwingCardNeedsARateButAnArchivedCashAccountDoesNot() = runTest(work) {
        val rig = Rig(listOf(account(1, "EUR", "-50", archived = true, card = true),
            account(2, "JPY", "100", archived = true)), emptyList())
        val vm = rig.vm()
        try {
            runCurrent()
            assertEquals(listOf("EUR"), vm.state.value.rows.map { it.currencyCode })
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun settledAndNegativeDebtsDoNotAskForRatesAndAnOpenDebtDoes() = runTest(work) {
        val rig = Rig(emptyList(), listOf(leg(1, "EUR", "0"), leg(2, "JPY", "-5")))
        val vm = rig.vm()
        try {
            runCurrent()
            assertTrue(vm.state.value.rows.isEmpty())
            rig.debtLegs.value = rig.debtLegs.value + leg(3, "EUR", "1", DebtSide.OWED_TO_ME)
            runCurrent()
            assertEquals(listOf("EUR"), vm.state.value.rows.map { it.currencyCode })
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun debtAndCreditBalanceChangesPreserveARateBeingTyped() = runTest(work) {
        val rig = Rig(listOf(account(1, "JPY", "-5", archived = true, card = true)), listOf(leg(1, "EUR", "10")))
        val vm = rig.vm()
        try {
            runCurrent()
            vm.setRateInput("EUR", "1.234")
            rig.debtLegs.value = listOf(leg(1, "EUR", "20"))
            rig.accounts.upsert(account(1, "JPY", "-6", archived = true, card = true))
            runCurrent()
            assertEquals(listOf("EUR", "JPY"), vm.state.value.rows.map { it.currencyCode })
            assertEquals("1.234", vm.state.value.rows.first { it.currencyCode == "EUR" }.input)
        } finally { vm.viewModelScope.cancel() }
    }

    private inner class Rig(initialAccounts: List<Account>, initialDebts: List<DebtLeg>) {
        private val accountRows = MutableStateFlow(initialAccounts)
        val accounts = object : AccountRepository {
            override fun observeAll(): Flow<List<Account>> = accountRows
            override suspend fun findById(id: Long): Account? = accountRows.value.firstOrNull { it.id == id }
            override suspend fun upsert(account: Account): Long {
                accountRows.value = accountRows.value.filterNot { it.id == account.id } + account
                return account.id
            }
            override suspend fun delete(id: Long) { accountRows.value = accountRows.value.filterNot { it.id == id } }
        }
        val debtLegs = MutableStateFlow(initialDebts)
        private val currencies = FakeCurrencyRepository(listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2), Currency("JPY", "¥", 0)))
        private val rates = FakeRateRepository()
        private val settings = FakeSettingsRepository(Settings(baseCurrencyCode = "USD"))
        private val ledger = object : LedgerQueries by FakeLedgerQueries(FakeEntryRepository()) {
            override fun observeDebtLegs() = debtLegs
        }
        val balances = GetAccountBalances(accounts, ledger)
        val summary = ComputeHomeSummary(ledger, FakeFxRepository(currencies, rates, settings))
        fun vm() = ManageRatesViewModel(balances, ledger, currencies, rates, settings,
            SetExchangeRate(currencies, rates, ledger, DirectTransactionRunner(), settings), MoneyParser(Locale.US), ObserveLedgerHealth(ledger), work, RecordingErrorReporter())
    }

    private fun account(id: Long, code: String, amount: String, archived: Boolean = false, card: Boolean = false) =
        Account(id, "Account $id", if (card) "credit_card" else "cash", code, Money.of(amount, code), archived = archived,
            creditLimit = if (card) Money.of("100", code) else null)

    private fun leg(id: Long, code: String, amount: String, side: DebtSide = DebtSide.I_OWE) =
        DebtLeg(id, side, Money.of(amount, code))
}
