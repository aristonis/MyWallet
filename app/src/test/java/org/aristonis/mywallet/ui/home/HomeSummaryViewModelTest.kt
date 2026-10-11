package org.aristonis.mywallet.ui.home

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.DebtLeg
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class HomeSummaryViewModelTest {
    private val work = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(work)
    @After fun tearDown() = Dispatchers.resetMain()

    private suspend fun TestScope.watch(rig: HomeSummaryRig, check: suspend (HomeViewModel) -> Unit) {
        val vm = rig.vm()
        try {
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            check(vm)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun showsAllFourFormattedAmountsWithANegativeBalance() = runTest(work) {
        val rig = HomeSummaryRig(listOf(summaryAccount(1, "700")), work)
        rig.debtLegs.value = listOf(DebtLeg(1, DebtSide.OWED_TO_ME, Money.of("300", "USD")),
            DebtLeg(2, DebtSide.I_OWE, Money.of("1450", "USD")))
        watch(rig) { vm ->
            assertEquals(HomeSummaryState.Amounts("700.00 USD", "300.00 USD", "1,450.00 USD", "-450.00 USD"), vm.state.value.summary)
            assertEquals("700.00 USD", vm.state.value.accounts.single().nativeDisplay)
            assertFalse(vm.state.value.isLoading)
        }
    }

    @Test fun oneAccountProjectionReadServesBothHomeConsumersOnEachSavedEntry() = runTest(work) {
        val rig = HomeSummaryRig(listOf(summaryAccount(1, "100")), work)
        watch(rig) { vm ->
            assertEquals(1, rig.accountProjectionReads)
            rig.spend("5")
            runCurrent()
            assertEquals(2, rig.accountProjectionReads)
            assertEquals("95.00 USD", vm.state.value.accounts.single().nativeDisplay)
            assertEquals(HomeSummaryState.Amounts("95.00 USD", "0.00 USD", "0.00 USD", "95.00 USD"), vm.state.value.summary)
            rig.fxState.value = rig.fxState.value.copy(ratesToBase = mapOf("EUR" to BigDecimal("2")))
            runCurrent()
            assertEquals(2, rig.accountProjectionReads)
        }
    }

    @Test fun anArchivedOwingCardStillCountsInIOweWhileActiveCreditKeepsItsAvailableAmount() = runTest(work) {
        val rig = HomeSummaryRig(listOf(summaryAccount(1, "700"),
            summaryAccount(2, "-150", type = "credit_card", archived = true, limit = "200"),
            summaryAccount(3, "-50", type = "credit_card", limit = "100")), work)
        watch(rig) { vm ->
            assertEquals(HomeSummaryState.Amounts("700.00 USD", "0.00 USD", "200.00 USD", "500.00 USD"), vm.state.value.summary)
            assertEquals(setOf(1L, 3L), vm.state.value.accounts.map { it.account.id }.toSet())
            val creditRow = vm.state.value.accounts.first { it.account.id == 3L }
            assertEquals(Money.of("50", "USD"), requireNotNull(creditRow.credit).available)
            assertEquals("50.00 USD", creditRow.availableDisplay)
            assertEquals("50.00 USD", creditRow.owedDisplay)
            assertFalse(requireNotNull(creditRow.credit).overLimit)
        }
    }

    @Test fun aCardOverItsLimitShowsZeroAvailableAndPreservesTheWarningFlag() = runTest(work) {
        val rig = HomeSummaryRig(listOf(summaryAccount(1, "-120", type = "credit_card", limit = "100")), work)
        watch(rig) { vm ->
            val row = vm.state.value.accounts.single()
            assertTrue(requireNotNull(row.credit).overLimit)
            assertEquals("0.00 USD", row.availableDisplay)
            assertEquals("120.00 USD", row.owedDisplay)
            assertEquals("20.00 USD", row.overLimitDisplay)
            assertEquals(HomeSummaryState.Amounts("0.00 USD", "0.00 USD", "120.00 USD", "-120.00 USD"), vm.state.value.summary)
        }
    }

    @Test fun missingDebtCurrencyRateKeepsAccountRowsAndRecoversOnTheSameSubscriber() = runTest(work) {
        val rig = HomeSummaryRig(listOf(summaryAccount(1, "700")), work)
        rig.debtLegs.value = listOf(DebtLeg(1, DebtSide.I_OWE, Money.of("100", "EUR")))
        watch(rig) { vm ->
            assertEquals(HomeSummaryState.MissingRate("EUR"), vm.state.value.summary)
            assertEquals("700.00 USD", vm.state.value.accounts.single().nativeDisplay)
            rig.fxState.value = rig.fxState.value.copy(ratesToBase = mapOf("EUR" to BigDecimal("2")))
            runCurrent()
            assertEquals(HomeSummaryState.Amounts("700.00 USD", "0.00 USD", "200.00 USD", "500.00 USD"), vm.state.value.summary)
        }
    }

    @Test fun negativeDebtWarningSurvivesAnUnrelatedMissingRateAndLaterRecovery() = runTest(work) {
        val rig = HomeSummaryRig(listOf(summaryAccount(1, "700")), work)
        rig.debtLegs.value = listOf(DebtLeg(9, DebtSide.I_OWE, Money.of("-3", "EUR")),
            DebtLeg(2, DebtSide.OWED_TO_ME, Money.of("-1", "USD")), DebtLeg(5, DebtSide.I_OWE, Money.of("100", "EUR")))
        watch(rig) { vm ->
            assertEquals(HomeSummaryState.MissingRate("EUR"), vm.state.value.summary)
            assertEquals(listOf(2L, 9L), vm.state.value.debtsBelowZero)
            rig.fxState.value = rig.fxState.value.copy(ratesToBase = mapOf("EUR" to BigDecimal("2")))
            runCurrent()
            assertEquals(HomeSummaryState.Amounts("700.00 USD", "0.00 USD", "200.00 USD", "500.00 USD"), vm.state.value.summary)
            assertEquals(listOf(2L, 9L), vm.state.value.debtsBelowZero)
        }
    }
}
