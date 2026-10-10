package org.aristonis.mywallet.ui.debt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.DebtState
import org.aristonis.mywallet.domain.model.DebtTotals
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.UnreadableRowRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class DebtsViewModelTest {
    private val work = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(work)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun sideSelectionSeparatesOpenAndSettledRowsAndKeepsNegativeDebtsAccessible() = runTest(work) {
        val ownedViewModels = mutableListOf<ViewModel>()
        try {
            val mine = screenDebt(1, "Ali")
            val theirs = screenDebt(2, "Sara", DebtSide.OWED_TO_ME)
            val settled = screenDebt(3, "Omar")
            val negative = screenDebt(4, "Zaid", code = "EUR")
            val rig = DebtScreenRig(listOf(mine, theirs, settled, negative, screenDebt(5, "Hidden")),
                listOf(screenOpening(mine), screenOpening(theirs, "20"), screenOpening(settled, "5"),
                    Transaction.WriteOff(6, settled.side, settled.id, Money.of("5", "USD"), 7, date = screenDay),
                    Transaction.WriteOff(7, negative.side, negative.id, Money.of("3", "EUR"), 7, date = screenDay)), work)
            val vm = rig.list().also { ownedViewModels.add(it) }
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertFalse(vm.state.value.isLoading)
            assertEquals(setOf(1L, 4L), vm.state.value.open.map { it.summary.debt.id }.toSet())
            assertEquals(listOf(3L), vm.state.value.settled.map { it.summary.debt.id })
            assertEquals("-3.00 EUR", vm.state.value.open.first { it.summary.debt.id == 4L }.remainingDisplay)
            assertEquals(DebtTotals.Amounts(Money.of("10", "USD"), Money.of("20", "USD")), vm.state.value.totals)
            vm.selectSide(DebtSide.OWED_TO_ME)
            runCurrent()
            assertEquals(DebtSide.OWED_TO_ME, vm.state.value.selectedSide)
            assertEquals(listOf(2L), vm.state.value.open.map { it.summary.debt.id })
            assertTrue(vm.state.value.settled.isEmpty())
        } finally {
            ownedViewModels.forEach { it.viewModelScope.cancel() }
        }
    }

    @Test fun missingRateKeepsNativeRowsAndRecoversAfterTheRateIsSet() = runTest(work) {
        val ownedViewModels = mutableListOf<ViewModel>()
        try {
            val debt = screenDebt(1, "Ali", code = "EUR")
            val rig = DebtScreenRig(listOf(debt), listOf(screenOpening(debt)), work)
            val vm = rig.list().also { ownedViewModels.add(it) }
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertEquals(DebtTotals.MissingRate("EUR"), vm.state.value.totals)
            assertEquals("10.00 EUR", vm.state.value.open.single().remainingDisplay)
            rig.fxState.value = rig.fxState.value.copy(ratesToBase = mapOf("EUR" to BigDecimal("1.25")))
            runCurrent()
            assertEquals(DebtTotals.Amounts(Money.of("12.50", "USD"), Money.zero("USD")), vm.state.value.totals)
        } finally {
            ownedViewModels.forEach { it.viewModelScope.cancel() }
        }
    }

    @Test fun aFailedReadReportsTheErrorAndRetryRecoversTheRowsWithoutCreatingAnEmptyWallet() = runTest(work) {
        val ownedViewModels = mutableListOf<ViewModel>()
        try {
            val debt = screenDebt(1, "Ali")
            val rig = DebtScreenRig(listOf(debt), listOf(screenOpening(debt)), work)
            rig.readFailure = IllegalStateException("unreadable debt row")
            val vm = rig.list().also { ownedViewModels.add(it) }
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertTrue(vm.state.value.loadFailed)
            assertFalse(vm.state.value.isLoading)
            assertEquals(1, rig.errors.reported.size)
            rig.readFailure = null
            vm.retry()
            runCurrent()
            assertFalse(vm.state.value.loadFailed)
            assertEquals(listOf(1L), vm.state.value.open.map { it.summary.debt.id })
        } finally {
            ownedViewModels.forEach { it.viewModelScope.cancel() }
        }
    }

    @Test fun ledgerHealthFailureBlocksTotalsAndRetryRecoversAfterRepair() = runTest(work) {
        val ownedViewModels = mutableListOf<ViewModel>()
        try {
            val debt = screenDebt(1, "Ali")
            val rig = DebtScreenRig(listOf(debt), listOf(screenOpening(debt)), work)
            rig.unknown.value = UnreadableRowRef(90, "UNKNOWN")
            val vm = rig.list().also { ownedViewModels.add(it) }
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertTrue(vm.state.value.loadFailed)
            rig.unknown.value = null
            vm.retry()
            runCurrent()
            assertFalse(vm.state.value.loadFailed)
            assertEquals(1, vm.state.value.open.size)
        } finally {
            ownedViewModels.forEach { it.viewModelScope.cancel() }
        }
    }

    @Test fun refreshTodayUsesTheNewZoneWithoutAnyWalletWrite() = runTest(work) {
        val ownedViewModels = mutableListOf<ViewModel>()
        try {
            val debt = screenDebt(1, "Ali").copy(dueDate = screenDay)
            val rig = DebtScreenRig(listOf(debt), listOf(screenOpening(debt)), work)
            rig.clock = Clock.fixed(Instant.parse("2026-10-12T23:00:00Z"), ZoneOffset.UTC)
            val vm = rig.list().also { ownedViewModels.add(it) }
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertEquals(DebtState.OPEN, vm.state.value.open.single().summary.state)
            rig.clock = rig.clock.withZone(ZoneOffset.ofHours(3))
            vm.refreshToday()
            runCurrent()
            assertEquals(DebtState.OVERDUE, vm.state.value.open.single().summary.state)
        } finally {
            ownedViewModels.forEach { it.viewModelScope.cancel() }
        }
    }
}
