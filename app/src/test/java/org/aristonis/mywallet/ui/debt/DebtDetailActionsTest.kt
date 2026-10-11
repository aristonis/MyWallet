package org.aristonis.mywallet.ui.debt

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.CategoryRole
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.message.UiMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebtDetailActionsTest {
    private val work = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(work)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun writeOffDefaultsToTheCorrectRoleAndInjectedTodayOnBothSides() = runTest(work) {
        DebtSide.entries.forEach { side ->
            withFixture(screenDebt(1, "Ali", side)) { f ->
                f.vm.openWriteOff()
                runCurrent()
                val dialog = requireNotNull(f.vm.state.value.writeOffDialog)
                assertEquals(if (side == DebtSide.I_OWE) 5L else 6L, dialog.selectedCategoryId)
                assertEquals(screenDay, dialog.date)
                val expectedKind = if (side == DebtSide.I_OWE) CategoryKind.INCOME else CategoryKind.EXPENSE
                assertTrue(dialog.categories.all { it.kind == expectedKind && !it.isSubCategory })
            }
        }
    }

    @Test fun aDeletedRoleLeavesTheCategoryEmptyAndRequiresAnExplicitReplacement() = runTest(work) {
        withFixture(screenDebt(1, "Sara", DebtSide.OWED_TO_ME)) { f ->
            f.roleLookup = { null }
            f.vm.openWriteOff()
            runCurrent()
            assertNull(requireNotNull(f.vm.state.value.writeOffDialog).selectedCategoryId)
            assertEquals(4, f.categories.observeAll().first().size)
            f.vm.confirmWriteOff()
            runCurrent()
            assertEquals(UiMessage.CategoryRequired, requireNotNull(f.vm.state.value.writeOffDialog).error)
            assertEquals(1, f.entries.stored.size)
            f.vm.selectWriteOffCategory(7)
            f.vm.setWriteOffDate(screenDay.minusDays(2))
            f.vm.confirmWriteOff()
            runCurrent()
            val saved = f.entries.stored.filterIsInstance<Transaction.WriteOff>().single()
            assertEquals(7L, saved.categoryId)
            assertEquals(screenDay.minusDays(2), saved.date)
            assertEquals(Money.of("100", "USD"), saved.amount)
            assertNull(f.vm.state.value.writeOffDialog)
        }
    }

    @Test fun wrongKindOrChildRoleBindingsLeaveTheCategoryEmpty() = runTest(work) {
        listOf(5L, 8L, 99L).forEach { binding ->
            withFixture(screenDebt(1, "Sara", DebtSide.OWED_TO_ME)) { f ->
                f.roleLookup = { binding }
                f.vm.openWriteOff()
                runCurrent()
                assertNull(requireNotNull(f.vm.state.value.writeOffDialog).selectedCategoryId)
                assertEquals(listOf(6L, 7L), requireNotNull(f.vm.state.value.writeOffDialog).categories.map { it.id })
            }
        }
    }

    @Test fun settledAndNegativeDebtsCannotOpenWriteOff() = runTest(work) {
        listOf("100", "120").forEach { amount ->
            val debt = screenDebt(1, "Ali")
            val initial = listOf(screenOpening(debt, "100"), payment(debt, amount))
            withFixture(debt, initial) { f ->
                f.vm.openWriteOff()
                runCurrent()
                assertNull(f.vm.state.value.writeOffDialog)
                assertEquals(2, f.entries.stored.size)
            }
        }
    }

    @Test fun confirmWriteOffReadsTheCurrentRestAfterTheDialogOpened() = runTest(work) {
        withFixture { f ->
            f.vm.openWriteOff()
            runCurrent()
            f.entries.insert(payment(f.debt, "40"))
            runCurrent()
            f.vm.confirmWriteOff()
            runCurrent()
            assertEquals(Money.of("60", "USD"), f.entries.stored.filterIsInstance<Transaction.WriteOff>().single().amount)
        }
    }

    @Test fun aWriteOffRefusalKeepsTheTypedMessageWithoutReportingPrivateDebtDetails() = runTest(work) {
        withFixture { f ->
            f.vm.openWriteOff()
            runCurrent()
            f.entries.insert(payment(f.debt, "100"))
            runCurrent()
            f.vm.confirmWriteOff()
            runCurrent()

            val expected = UiMessage.DebtHasNoRemaining("Ali")
            assertEquals(expected, f.vm.state.value.error)
            assertEquals(expected, requireNotNull(f.vm.state.value.writeOffDialog).error)
            assertTrue(f.entries.stored.filterIsInstance<Transaction.WriteOff>().isEmpty())
            f.errors.reported.forEach { reported ->
                val diagnostic = generateSequence(reported) { it.cause }.joinToString { it.toString() }
                assertFalse("Counterparty leaked to the error reporter", diagnostic.contains("Ali"))
                assertFalse("Debt amount leaked to the error reporter", diagnostic.contains("100"))
            }
        }
    }

    @Test fun aCategoryDeletedAfterOpeningIsRefusedAndTheDialogCanBeRetried() = runTest(work) {
        withFixture(screenDebt(1, "Sara", DebtSide.OWED_TO_ME)) { f ->
            f.vm.openWriteOff()
            runCurrent()
            f.removeCategory(6)
            f.vm.confirmWriteOff()
            runCurrent()
            assertNotNull(f.vm.state.value.writeOffDialog)
            assertEquals(UiMessage.CategoryNotFound, requireNotNull(f.vm.state.value.writeOffDialog).error)
            assertEquals(1, f.entries.stored.size)
            f.vm.selectWriteOffCategory(7)
            f.vm.confirmWriteOff()
            runCurrent()
            assertEquals(7L, f.entries.stored.filterIsInstance<Transaction.WriteOff>().single().categoryId)
        }
    }

    @Test fun deleteUsesFreshHistoryAndUndoRestoresEntriesWithoutReplacingNewerMetadata() = runTest(work) {
        val original = screenDebt(1, "Ali").copy(dueDate = screenDay, note = "original")
        withFixture(original) { f ->
            f.vm.openDeleteDebt()
            runCurrent()
            assertEquals(1, requireNotNull(f.vm.state.value.deleteDialog).preview.entries.size)
            f.entries.insert(screenOpening(original, "5", 3))
            f.vm.confirmDeleteDebt()
            runCurrent()
            assertTrue(f.entries.stored.isEmpty())
            assertEquals(2, requireNotNull(f.vm.state.value.undoableDelete).entries.size)
            assertNull(f.debtStore.findById(1)?.note)
            f.debtStore.updateDetails(1, null, "new note")
            f.vm.undoDeleteDebt()
            runCurrent()
            assertEquals(2, f.entries.stored.size)
            assertEquals(original.copy(note = "new note"), f.debtStore.findById(1))
            assertNull(f.vm.state.value.undoableDelete)
        }
    }

    @Test fun dismissingIdleDialogsLeavesTheDebtHistoryAndDetailsUntouched() = runTest(work) {
        withFixture { f ->
            val original = f.entries.stored
            f.vm.openWriteOff()
            runCurrent()
            assertNotNull(f.vm.state.value.writeOffDialog)
            f.vm.dismissWriteOff()
            assertNull(f.vm.state.value.writeOffDialog)
            f.vm.openDeleteDebt()
            runCurrent()
            assertNotNull(f.vm.state.value.deleteDialog)
            f.vm.dismissDeleteDebt()

            assertNull(f.vm.state.value.deleteDialog)
            assertEquals(original, f.entries.stored)
            assertEquals(0, f.deleteCalls)
            assertEquals(0, f.detailsCalls)
            assertFalse(f.vm.state.value.isSubmitting)
        }
    }

    @Test fun finalizingADeleteClosesTheUndoOpportunityAndMarksTheScreenDone() = runTest(work) {
        withFixture { f ->
            f.vm.openDeleteDebt()
            runCurrent()
            f.vm.confirmDeleteDebt()
            runCurrent()
            assertNotNull(f.vm.state.value.undoableDelete)
            assertFalse(f.vm.state.value.deleted)

            f.vm.confirmDeleted()

            assertNull(f.vm.state.value.undoableDelete)
            assertTrue(f.vm.state.value.deleted)
            assertTrue(f.entries.stored.isEmpty())
            assertEquals(1, f.deleteCalls)
        }
    }

    @Test fun aDeleteRefusalStaysInTheDialogAndUnlocksForRetryWithoutAnUndoBundle() = runTest(work) {
        withFixture { f ->
            val original = f.entries.stored
            val shortfall = Money.of("5", "USD")
            f.vm.openDeleteDebt()
            runCurrent()
            f.beforeDelete = { throw WalletException.DebtBelowZero("Ali", shortfall) }
            f.vm.confirmDeleteDebt()
            runCurrent()

            val expected = UiMessage.DebtBelowZero("Ali", shortfall)
            assertEquals(expected, requireNotNull(f.vm.state.value.deleteDialog).error)
            assertEquals(expected, f.vm.state.value.error)
            assertNull(f.vm.state.value.undoableDelete)
            assertFalse(f.vm.state.value.isSubmitting)
            assertEquals(original, f.entries.stored)
            f.beforeDelete = {}
            f.vm.confirmDeleteDebt()
            runCurrent()
            assertNull(f.vm.state.value.deleteDialog)
            assertNotNull(f.vm.state.value.undoableDelete)
            assertNull(f.vm.state.value.error)
            assertTrue(f.entries.stored.isEmpty())
        }
    }

    @Test fun failedUndoKeepsItsBundleSoTheUserCanRetry() = runTest(work) {
        withFixture { f ->
            f.vm.openDeleteDebt()
            runCurrent()
            f.vm.confirmDeleteDebt()
            runCurrent()
            val removed = requireNotNull(f.vm.state.value.undoableDelete)
            f.entries.beforeInsert = { throw WalletException.AccountNotFound(1) }
            f.vm.undoDeleteDebt()
            runCurrent()
            assertEquals(removed, f.vm.state.value.undoableDelete)
            assertEquals(UiMessage.AccountNotFound, f.vm.state.value.error)
            assertFalse(f.vm.state.value.isSubmitting)
            f.entries.beforeInsert = {}
            f.vm.undoDeleteDebt()
            runCurrent()
            assertNull(f.vm.state.value.undoableDelete)
            assertEquals(1, f.entries.stored.size)
        }
    }

    @Test fun aFailedUndoSurfacesExactlyOneMessageThroughTheUndoSnackbar() = runTest(work) {
        withFixture { f ->
            f.vm.openDeleteDebt()
            runCurrent()
            f.vm.confirmDeleteDebt()
            runCurrent()
            assertFalse(f.vm.state.value.undoFailed) // a clean delete shows only the "deleted" snackbar
            f.entries.beforeInsert = { throw WalletException.AccountNotFound(1) }
            f.vm.undoDeleteDebt()
            runCurrent()

            val state = f.vm.state.value
            assertTrue(state.undoFailed) // the snackbar is the one message: "Undo failed" with Undo to retry
            assertNull(state.inlineError) // no generic error row beside it
        }
    }

    @Test fun aMetadataFailureStillShowsItsInlineErrorWhenNoUndoIsPending() = runTest(work) {
        withFixture { _ ->
            val state = DebtDetailUiState(error = UiMessage.AccountNotFound)
            assertFalse(state.undoFailed)
            assertEquals(UiMessage.AccountNotFound, state.inlineError)
        }
    }

    @Test fun aPendingWriteOffBlocksAllCompetingWritesAndDialogActions() = runTest(work) {
        withFixture { f ->
            val gate = CompletableDeferred<Unit>()
            f.vm.openWriteOff()
            runCurrent()
            f.entries.beforeInsert = { gate.await() }
            try {
                f.vm.confirmWriteOff()
                runCurrent()
                assertTrue(f.vm.state.value.isSubmitting)
                f.vm.confirmWriteOff()
                f.vm.rename()
                f.vm.saveDetails()
                f.vm.openDeleteDebt()
                f.vm.dismissWriteOff()
                runCurrent()
                assertEquals(0, f.renameCalls)
                assertEquals(0, f.detailsCalls)
                assertNull(f.vm.state.value.deleteDialog)
                assertNotNull(f.vm.state.value.writeOffDialog)
            } finally { gate.complete(Unit); runCurrent() }
            assertEquals(1, f.entries.stored.filterIsInstance<Transaction.WriteOff>().size)
        }
    }

    @Test fun aPendingUndoBlocksDeletionAndMetadataWrites() = runTest(work) {
        withFixture { f ->
            f.vm.openDeleteDebt()
            runCurrent()
            f.vm.confirmDeleteDebt()
            runCurrent()
            val gate = CompletableDeferred<Unit>()
            f.entries.beforeInsert = { gate.await() }
            try {
                f.vm.undoDeleteDebt()
                runCurrent()
                assertTrue(f.vm.state.value.isSubmitting)
                f.vm.undoDeleteDebt()
                f.vm.openDeleteDebt()
                f.vm.rename()
                f.vm.saveDetails()
                runCurrent()
                assertEquals(0, f.renameCalls)
                assertEquals(1, f.detailsCalls)
                assertNull(f.vm.state.value.deleteDialog)
            } finally { gate.complete(Unit); runCurrent() }
            assertFalse(f.vm.state.value.isSubmitting)
            assertEquals(1, f.entries.stored.size)
        }
    }

    @Test fun aLateRoleLookupCannotOpenTheOldDebtsDialogAfterAnotherDebtLoads() = runTest(work) {
        withFixture { f ->
            val gate = CompletableDeferred<Unit>()
            f.roleLookup = { withContext(NonCancellable) { gate.await(); 5L } }
            try {
                f.vm.openWriteOff()
                runCurrent()
                f.vm.load(2)
                runCurrent()
                assertEquals(2L, f.vm.state.value.detail?.summary?.debt?.id)
            } finally { gate.complete(Unit); runCurrent() }
            assertNull(f.vm.state.value.writeOffDialog)
            assertEquals(2L, f.vm.state.value.detail?.summary?.debt?.id)
        }
    }

    private suspend fun TestScope.withFixture(debt: Debt = screenDebt(1, "Ali"),
        initial: List<Transaction> = listOf(screenOpening(debt, "100")), block: suspend (DebtActionFixture) -> Unit) {
        val fixture = DebtActionFixture(work, debt, initial)
        try { fixture.vm.load(debt.id); runCurrent(); block(fixture) }
        finally { fixture.vm.viewModelScope.cancel() }
    }
    private fun payment(debt: Debt, amount: String) = Transaction.Repayment(id = 2, side = debt.side,
        accountId = 1, debtId = debt.id, accountAmount = Money.of(amount, "USD"), principal = Money.of(amount, "USD"),
        date = screenDay)
}

