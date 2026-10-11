package org.aristonis.mywallet.ui.rates

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.RecordingErrorReporter
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.port.DebtLeg
import org.aristonis.mywallet.domain.port.AccountLeg
import org.aristonis.mywallet.domain.usecase.GetAccountBalances
import org.aristonis.mywallet.domain.usecase.SetExchangeRate
import org.aristonis.mywallet.ui.HealthLedger
import org.aristonis.mywallet.ui.transaction.DirectTransactionRunner
import org.aristonis.mywallet.ui.transaction.FakeAccountRepository
import org.aristonis.mywallet.ui.transaction.FakeCurrencyRepository
import org.aristonis.mywallet.ui.transaction.FakeRateRepository
import org.aristonis.mywallet.ui.transaction.FakeSettingsRepository
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class RatesReadFailureTest {
    private val work = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(work)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun unreadableDebtLegsStopLoadingAndHideRateFieldsInsteadOfEscapingTheScreen() = runTest(work) {
        val ledger = object : HealthLedger() {
            override fun observeDebtLegs(): Flow<List<DebtLeg>> = flow {
                throw WalletException.UnreadableRow(9, "DEBT")
            }
        }
        val vm = vm(ledger)
        try {
            runCurrent()
            assertFalse("a failed read must stop the loading indicator", vm.state.value.isLoading)
            assertTrue(vm.state.value.loadFailed)
            assertTrue("rates from unreadable projections must not be editable", vm.state.value.rows.isEmpty())
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun anUnknownEntryKindWhileRatesAreOpenHidesTheProjectionBasedFields() = runTest(work) {
        val ledger = HealthLedger()
        val vm = vm(ledger)
        try {
            runCurrent()
            assertFalse(vm.state.value.rows.isEmpty())
            ledger.plantUnknownKind(7, "FROM_THE_FUTURE")
            runCurrent()
            assertTrue("unknown kinds make the wallet unreadable", vm.state.value.rows.isEmpty())
            assertTrue(vm.state.value.loadFailed)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun retryRereadsTheFailedProjectionAndRestoresTheRateBeingTyped() = runTest(work) {
        var fails = false
        var reads = 0
        val ledger = object : HealthLedger() {
            override fun observeDebtLegs(): Flow<List<DebtLeg>> = flow {
                reads++
                emitAll(debtLegs.map {
                    if (fails) throw WalletException.UnreadableRow(9, "DEBT")
                    it
                })
            }
        }
        val errors = RecordingErrorReporter()
        val vm = vm(ledger, errors)
        try {
            runCurrent()
            vm.setRateInput("EUR", "1.234")
            fails = true
            ledger.debtLegs.value = listOf(DebtLeg(9, DebtSide.I_OWE, Money.of("5", "EUR")))
            runCurrent()
            assertTrue(vm.state.value.loadFailed)
            assertEquals("UnreadableRow", errors.reported.single().message)
            val readsBefore = reads
            fails = false
            vm.retry()
            runCurrent()
            assertFalse(vm.state.value.loadFailed)
            assertFalse(vm.state.value.isLoading)
            assertEquals(readsBefore + 1, reads)
            assertEquals("1.234", vm.state.value.rows.single().input)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun anUnreadableAccountProjectionAlsoShowsTheRecoverableFailure() = runTest(work) {
        val ledger = object : HealthLedger() {
            override fun observeAccountLegs(): Flow<List<AccountLeg>> = flow {
                throw WalletException.UnreadableRow(8, "ACCOUNT")
            }
        }
        val errors = RecordingErrorReporter()
        val vm = vm(ledger, errors)
        try {
            runCurrent()
            assertTrue(vm.state.value.loadFailed)
            assertEquals("UnreadableRow", errors.reported.single().message)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun aCanceledHealthReadIsNotReportedAsWalletCorruption() = runTest(work) {
        val ledger = HealthLedger().also { it.healthFailure = CancellationException("screen closed") }
        val errors = RecordingErrorReporter()
        val vm = vm(ledger, errors)
        try {
            runCurrent()
            assertFalse(vm.state.value.loadFailed)
            assertTrue(errors.reported.isEmpty())
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun readDiagnosticsKeepTheFailureTypeWithoutFinancialMessagesCausesOrSuppressedData() = runTest(work) {
        val financial = "credit limit 8723.91 EUR"
        val nested = "private account savings-481"
        val suppressed = "private stored rate 17.294"
        val cause = IllegalArgumentException(financial, IllegalStateException(nested))
        val failures = listOf(
            WalletException.UnreadableRow(9, "DEBT", cause),
            IllegalArgumentException(financial, IllegalStateException(nested)),
        )
        failures.forEach { failure ->
            failure.addSuppressed(IllegalStateException(suppressed))
            val errors = RecordingErrorReporter()
            val ledger = HealthLedger().also { it.healthFailure = failure }
            val vm = vm(ledger, errors)
            try {
                runCurrent()
                assertTrue(vm.state.value.loadFailed)
                val diagnostic = errors.reported.single()
                assertEquals(failure.javaClass.simpleName, diagnostic.message)
                assertNotSame(failure, diagnostic)
                assertNull(diagnostic.cause)
                assertTrue(diagnostic.suppressed.isEmpty())
                val rendered = StringWriter().also { diagnostic.printStackTrace(PrintWriter(it)) }.toString()
                listOf(financial, nested, suppressed).forEach { value -> assertFalse(rendered.contains(value)) }
            } finally { vm.viewModelScope.cancel() }
        }
    }

    private fun vm(ledger: HealthLedger, errors: RecordingErrorReporter = RecordingErrorReporter()): ManageRatesViewModel {
        val accounts = FakeAccountRepository(listOf(Account(1, "Euros", "cash", "EUR", Money.of("10", "EUR"))))
        val currencies = FakeCurrencyRepository(listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)))
        val rates = FakeRateRepository()
        val settings = FakeSettingsRepository(Settings(baseCurrencyCode = "USD"))
        return ManageRatesViewModel(GetAccountBalances(accounts, ledger), ledger, currencies, rates, settings,
            SetExchangeRate(currencies, rates, ledger, DirectTransactionRunner(), settings), MoneyParser(Locale.US),
            ledger.health(), work, errors)
    }
}
