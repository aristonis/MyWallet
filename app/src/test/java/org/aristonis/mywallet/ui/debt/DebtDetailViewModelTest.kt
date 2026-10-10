package org.aristonis.mywallet.ui.debt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.UnreadableRowRef
import org.aristonis.mywallet.ui.message.UiMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class DebtDetailViewModelTest {
    private val work = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(work)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun loadsTheRequestedDebtAndItsNewestFirstHistory() = runTest(work) {
        val ownedViewModels = mutableListOf<ViewModel>()
        try {
            val debt = screenDebt(1, "Ali").copy(dueDate = screenDay, note = "rent")
            val rig = DebtScreenRig(listOf(debt), listOf(screenOpening(debt, id = 2), screenOpening(debt, id = 1)), work)
            val vm = rig.detail().also { ownedViewModels.add(it) }
            backgroundScope.launch { vm.state.collect {} }
            vm.load(1)
            runCurrent()
            assertEquals(debt, vm.state.value.detail!!.summary.debt)
            assertEquals(listOf(2L, 1L), vm.state.value.detail!!.entries.map { it.id })
            assertEquals("Ali", vm.state.value.counterpartyInput)
            assertEquals(screenDay, vm.state.value.dueDate)
            assertEquals("rent", vm.state.value.noteInput)
        } finally {
            ownedViewModels.forEach { it.viewModelScope.cancel() }
        }
    }

    @Test fun renameCollisionPreservesTheDraftAndChangingItAllowsTheRename() = runTest(work) {
        val ownedViewModels = mutableListOf<ViewModel>()
        try {
            val debt = screenDebt(1, "Ali")
            val rig = DebtScreenRig(listOf(debt, screenDebt(2, "Omar")), listOf(screenOpening(debt)), work)
            val vm = rig.detail().also { ownedViewModels.add(it) }
            backgroundScope.launch { vm.state.collect {} }
            vm.load(1)
            runCurrent()
            vm.setCounterparty("Omar")
            vm.rename()
            runCurrent()
            assertEquals(UiMessage.DebtNameTaken("Omar"), vm.state.value.error)
            assertEquals("Omar", vm.state.value.counterpartyInput)
            assertEquals("Ali", rig.debtStore.findById(1)!!.counterparty)
            vm.setCounterparty("Sara")
            vm.rename()
            runCurrent()
            assertNull(vm.state.value.error)
            assertEquals("Sara", rig.debtStore.findById(1)!!.counterparty)
        } finally {
            ownedViewModels.forEach { it.viewModelScope.cancel() }
        }
    }

    @Test fun unsavedMetadataAndNameDraftsSurviveHistoryAndFxEmissions() = runTest(work) {
        val ownedViewModels = mutableListOf<ViewModel>()
        try {
            val debt = screenDebt(1, "Ali")
            val rig = DebtScreenRig(listOf(debt), listOf(screenOpening(debt)), work)
            val vm = rig.detail().also { ownedViewModels.add(it) }
            backgroundScope.launch { vm.state.collect {} }
            vm.load(1)
            runCurrent()
            vm.setCounterparty("My pending name")
            vm.setDueDate(screenDay.plusDays(5))
            vm.setNote("My pending note")
            rig.entries.insert(screenOpening(debt, "3", id = 2))
            rig.fxState.value = rig.fxState.value.copy(ratesToBase = mapOf("EUR" to BigDecimal("1.25")))
            runCurrent()
            assertEquals(Money.of("13", "USD"), vm.state.value.detail!!.summary.remaining)
            assertEquals("My pending name", vm.state.value.counterpartyInput)
            assertEquals(screenDay.plusDays(5), vm.state.value.dueDate)
            assertEquals("My pending note", vm.state.value.noteInput)
        } finally {
            ownedViewModels.forEach { it.viewModelScope.cancel() }
        }
    }

    @Test fun saveDetailsIsSeparateFromRenameAndNullClearsTheDueDate() = runTest(work) {
        val ownedViewModels = mutableListOf<ViewModel>()
        try {
            val debt = screenDebt(1, "Ali").copy(dueDate = screenDay, note = "old")
            val rig = DebtScreenRig(listOf(debt), listOf(screenOpening(debt)), work)
            val vm = rig.detail().also { ownedViewModels.add(it) }
            backgroundScope.launch { vm.state.collect {} }
            vm.load(1)
            runCurrent()
            vm.setCounterparty("Pending rename")
            vm.setDueDate(null)
            vm.setNote("updated note")
            vm.saveDetails()
            runCurrent()
            assertEquals(debt.copy(dueDate = null, note = "updated note"), rig.debtStore.findById(1))
            assertEquals("Pending rename", vm.state.value.counterpartyInput)
        } finally {
            ownedViewModels.forEach { it.viewModelScope.cancel() }
        }
    }

    @Test fun unknownKindShowsReadFailureAndRetryRecoversTheSameDebt() = runTest(work) {
        val ownedViewModels = mutableListOf<ViewModel>()
        try {
            val debt = screenDebt(1, "Ali")
            val rig = DebtScreenRig(listOf(debt), listOf(screenOpening(debt)), work)
            rig.unknown.value = UnreadableRowRef(90, "UNKNOWN")
            val vm = rig.detail().also { ownedViewModels.add(it) }
            backgroundScope.launch { vm.state.collect {} }
            vm.load(1)
            runCurrent()
            assertTrue(vm.state.value.loadFailed)
            assertEquals(1, rig.errors.reported.size)
            rig.unknown.value = null
            vm.retry()
            runCurrent()
            assertFalse(vm.state.value.loadFailed)
            assertEquals(1L, vm.state.value.detail!!.summary.debt.id)
        } finally {
            ownedViewModels.forEach { it.viewModelScope.cancel() }
        }
    }

    @Test fun missingDebtIsDifferentFromAnUnreadableWallet() = runTest(work) {
        val ownedViewModels = mutableListOf<ViewModel>()
        try {
            val rig = DebtScreenRig(emptyList(), emptyList(), work)
            val vm = rig.detail().also { ownedViewModels.add(it) }
            backgroundScope.launch { vm.state.collect {} }
            vm.load(99)
            runCurrent()
            assertFalse(vm.state.value.loadFailed)
            assertFalse(vm.state.value.isLoading)
            assertEquals(UiMessage.DebtNotFound, vm.state.value.error)
            assertNull(vm.state.value.detail)
        } finally {
            ownedViewModels.forEach { it.viewModelScope.cancel() }
        }
    }

    @Test fun failedMetadataWriteIsReportedAndTheDraftCanBeRetried() = runTest(work) {
        val ownedViewModels = mutableListOf<ViewModel>()
        try {
            val debt = screenDebt(1, "Ali")
            val rig = DebtScreenRig(listOf(debt), listOf(screenOpening(debt)), work)
            val vm = rig.detail().also { ownedViewModels.add(it) }
            backgroundScope.launch { vm.state.collect {} }
            vm.load(1)
            runCurrent()
            vm.setNote("pending")
            rig.detailsFailure = WalletException.DebtNotFound(1)
            vm.saveDetails()
            runCurrent()
            assertEquals(UiMessage.DebtNotFound, vm.state.value.error)
            assertEquals("pending", vm.state.value.noteInput)
            rig.detailsFailure = null
            vm.saveDetails()
            runCurrent()
            assertEquals("pending", rig.debtStore.findById(1)!!.note)
        } finally {
            ownedViewModels.forEach { it.viewModelScope.cancel() }
        }
    }
    @Test fun cleanFieldsTrackExternalChangesWhileTheDirtyNameDraftIsKept() = runTest(work) {
        val ownedViewModels = mutableListOf<ViewModel>()
        try {
            val debt = screenDebt(1, "Ali")
            val rig = DebtScreenRig(listOf(debt), listOf(screenOpening(debt)), work)
            val vm = rig.detail().also { ownedViewModels.add(it) }
            backgroundScope.launch { vm.state.collect {} }
            vm.load(1)
            runCurrent()
            rig.debtStore.rename(1, "Updated externally")
            runCurrent()
            assertEquals("Updated externally", vm.state.value.counterpartyInput)
            vm.setCounterparty("My draft")
            rig.debtStore.rename(1, "Changed again")
            rig.debtStore.updateDetails(1, screenDay.plusDays(3), "external note")
            runCurrent()
            assertEquals("My draft", vm.state.value.counterpartyInput)
            assertEquals(screenDay.plusDays(3), vm.state.value.dueDate)
            assertEquals("external note", vm.state.value.noteInput)
        } finally {
            ownedViewModels.forEach { it.viewModelScope.cancel() }
        }
    }

    @Test fun aMissingDebtErrorClearsWhenTheRowBecomesReadable() = runTest(work) {
        val ownedViewModels = mutableListOf<ViewModel>()
        try {
            val rig = DebtScreenRig(emptyList(), emptyList(), work)
            val vm = rig.detail().also { ownedViewModels.add(it) }
            backgroundScope.launch { vm.state.collect {} }
            vm.load(1)
            runCurrent()
            assertEquals(UiMessage.DebtNotFound, vm.state.value.error)
            rig.debtStore.getOrCreate("Ali", org.aristonis.mywallet.domain.model.DebtSide.I_OWE, "USD")
            runCurrent()
            assertNull(vm.state.value.error)
            assertEquals("Ali", vm.state.value.counterpartyInput)
        } finally {
            ownedViewModels.forEach { it.viewModelScope.cancel() }
        }
    }

    @Test fun aPendingDetailsWriteRejectsDuplicateSubmitAndKeepsANewerDraft() = runTest(work) {
        val ownedViewModels = mutableListOf<ViewModel>()
        try {
            val debt = screenDebt(1, "Ali")
            val rig = DebtScreenRig(listOf(debt), listOf(screenOpening(debt)), work)
            val gate = CompletableDeferred<Unit>()
            rig.beforeDetails = { gate.await() }
            val vm = rig.detail().also { ownedViewModels.add(it) }
            backgroundScope.launch { vm.state.collect {} }
            vm.load(1)
            runCurrent()
            vm.setNote("submitted note")
            vm.saveDetails()
            runCurrent()
            assertTrue(vm.state.value.isSubmitting)
            vm.saveDetails()
            vm.rename()
            vm.setNote("newer unsaved note")
            runCurrent()
            assertEquals(1, rig.detailsCalls)
            assertEquals(0, rig.renameCalls)
            gate.complete(Unit)
            runCurrent()
            assertFalse(vm.state.value.isSubmitting)
            assertEquals("submitted note", rig.debtStore.findById(1)!!.note)
            assertEquals("newer unsaved note", vm.state.value.noteInput)
        } finally {
            ownedViewModels.forEach { it.viewModelScope.cancel() }
        }
    }

    @Test fun blankNoteClearsSavedDetailsAndLoadingAnotherDebtResetsThePreviousDrafts() = runTest(work) {
        val ownedViewModels = mutableListOf<ViewModel>()
        try {
            val first = screenDebt(1, "Ali").copy(dueDate = screenDay, note = "old note")
            val second = screenDebt(2, "Sara").copy(note = "second note")
            val rig = DebtScreenRig(listOf(first, second), listOf(screenOpening(first), screenOpening(second)), work)
            val vm = rig.detail().also { ownedViewModels.add(it) }
            backgroundScope.launch { vm.state.collect {} }
            vm.load(1)
            runCurrent()
            vm.setNote("   ")
            vm.setDueDate(null)
            vm.saveDetails()
            runCurrent()
            assertNull(rig.debtStore.findById(1)!!.note)
            assertNull(rig.debtStore.findById(1)!!.dueDate)
            vm.setCounterparty("Unsaved first name")
            vm.load(2)
            runCurrent()
            assertEquals("Sara", vm.state.value.counterpartyInput)
            assertEquals("second note", vm.state.value.noteInput)
            assertNull(vm.state.value.dueDate)
        } finally {
            ownedViewModels.forEach { it.viewModelScope.cancel() }
        }
    }

    @Test fun aRenameFailureSurvivesUnrelatedHistoryUpdatesAndRetryKeepsMetadataDrafts() = runTest(work) {
        val ownedViewModels = mutableListOf<ViewModel>()
        try {
            val debt = screenDebt(1, "Ali")
            val rig = DebtScreenRig(listOf(debt, screenDebt(2, "Omar")), listOf(screenOpening(debt)), work)
            val vm = rig.detail().also { ownedViewModels.add(it) }
            backgroundScope.launch { vm.state.collect {} }
            vm.load(1)
            runCurrent()
            vm.setCounterparty("Omar")
            vm.setNote("pending note")
            vm.rename()
            runCurrent()
            rig.entries.insert(screenOpening(debt, "2", 3))
            runCurrent()
            assertEquals(UiMessage.DebtNameTaken("Omar"), vm.state.value.error)
            assertEquals("pending note", vm.state.value.noteInput)
            vm.retry()
            runCurrent()
            assertEquals("Omar", vm.state.value.counterpartyInput)
            assertEquals("pending note", vm.state.value.noteInput)
            assertEquals(UiMessage.DebtNameTaken("Omar"), vm.state.value.error)
        } finally {
            ownedViewModels.forEach { it.viewModelScope.cancel() }
        }
    }

}
