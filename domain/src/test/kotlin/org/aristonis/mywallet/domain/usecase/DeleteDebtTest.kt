package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.DebtRepository
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.DebtLeg
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.usecase.fake.InjectedFault
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class DeleteDebtTest {
    @Test fun removesAllOwnEntriesClearsDetailsKeepsTheDebtAndUndoRestoresExactSnapshotsOnBothSides() = runTest {
        for (side in DebtSide.entries) {
            val debt = deletionDebt(side)
            val opening = deletionOpening(debt)
            val paid = deletionPayment(debt)
            val rig = SaveEntryRig(initialEntries = listOf(opening, paid), initialDebts = listOf(debt), atomic = true)
            val undo = DeleteDebt(rig.runner, rig.debts, rig.deletionEntries(), rig.ledger)(1)
            assertTrue(rig.entries.rows.value.isEmpty())
            assertEquals(debt.copy(dueDate = null, note = null), rig.debts.stored.single())
            assertEquals(listOf(paid, opening), undo.entries)
            assertEquals(listOf(debt), undo.debts)
            rig.restoreEntries(undo)
            assertEquals(setOf(opening, paid), setOf(rig.entries.findById(1), rig.entries.findById(2)))
            assertEquals(debt, rig.debts.stored.single())
        }
    }

    @Test fun deletesTheBatchBeforeGuardingEvenWhenDeletingTheNewestLoanFirstWouldTemporarilyGoNegative() = runTest {
        val debt = deletionDebt()
        val loans = listOf(deletionOpening(debt, 1, "10", deletionDay.plusDays(2)),
            deletionOpening(debt, 2, "5", deletionDay.plusDays(1)))
        val payment = deletionPayment(debt, 3, "12", deletionDay.minusDays(1))
        val rig = SaveEntryRig(initialEntries = loans + payment, initialDebts = listOf(debt), atomic = true)
        val undo = DeleteDebt(rig.runner, rig.debts, rig.deletionEntries(), rig.ledger)(1)
        assertEquals(listOf(1L, 2L, 3L), undo.entries.map { it.id })
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test fun guardsTheFinalBatchExactlyOnceAfterEveryOwnEntryIsGone() = runTest {
        val debt = deletionDebt()
        val rig = SaveEntryRig(initialEntries = listOf(deletionOpening(debt), deletionPayment(debt)),
            initialDebts = listOf(debt), atomic = true)
        val readSizes = mutableListOf<Int>()
        val ledger = object : LedgerQueries by rig.ledger {
            override suspend fun debtLegs(debtIds: Set<Long>): List<DebtLeg> {
                readSizes.add(rig.entries.rows.value.size)
                return rig.ledger.debtLegs(debtIds)
            }
        }
        DeleteDebt(rig.runner, rig.debts, rig.deletionEntries(), ledger)(1)
        assertEquals(1, readSizes.count { it == 0 })
        assertEquals(0, readSizes.last())
    }

    @Test fun rereadsCurrentEntriesInsteadOfTrustingAnEarlierPreview() = runTest {
        val debt = deletionDebt()
        val rig = SaveEntryRig(initialEntries = listOf(deletionOpening(debt)), initialDebts = listOf(debt), atomic = true)
        val earlier = PreviewDebtDeletion(rig.runner, rig.debts, rig.deletionEntries(), rig.ledger)(1)
        val addedId = rig.entries.insert(deletionOpening(debt, id = 0, amount = "2"))
        val undo = DeleteDebt(rig.runner, rig.debts, rig.deletionEntries(), rig.ledger)(1)
        assertEquals(1, earlier.entries.size)
        assertEquals(setOf(1L, addedId), undo.entries.map { it.id }.toSet())
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test fun retainsUndecodableIncomeAndUnrelatedEntriesWithoutLoadingThem() = runTest {
        val debt = deletionDebt()
        val other = debt.copy(id = 2, counterparty = "Omar")
        val rig = SaveEntryRig(initialEntries = listOf(deletionOpening(debt), deletionOpening(other, id = 2)),
            initialDebts = listOf(debt, other), atomic = true)
        rig.retainRawIncome(debt)
        val preserved = rig.entries.rows.value.filter { it.header.id != 1L }
        val undo = DeleteDebt(rig.runner, rig.debts, rig.deletionEntries(), rig.ledger)(1)
        assertEquals(preserved, rig.entries.rows.value)
        assertEquals(listOf(1L), undo.entries.map { it.id })
        assertEquals(other, rig.debts.stored.first { it.id == 2L })
    }

    @Test fun hiddenDebtDeletionStillClearsDetailsAndUndoRestoresThem() = runTest {
        val debt = deletionDebt()
        val rig = SaveEntryRig(initialDebts = listOf(debt), atomic = true)
        val undo = DeleteDebt(rig.runner, rig.debts, rig.deletionEntries(), rig.ledger)(1)
        assertTrue(undo.entries.isEmpty())
        assertEquals(debt.copy(dueDate = null, note = null), rig.debts.stored.single())
        rig.restoreEntries(undo)
        assertEquals(debt, rig.debts.stored.single())
    }

    @Test fun aLateDeleteFailureRollsBackEarlierDeletesAndLeavesDetailsUnchanged() = runTest {
        val debt = deletionDebt()
        val rig = SaveEntryRig(initialEntries = listOf(deletionOpening(debt), deletionPayment(debt)), initialDebts = listOf(debt), atomic = true)
        val before = rig.entries.rows.value
        val failing = object : EntryRepository by rig.deletionEntries() {
            override suspend fun delete(id: Long) {
                rig.entries.delete(id)
                if (id == 1L) throw InjectedFault()
            }
        }
        try {
            DeleteDebt(rig.runner, rig.debts, failing, rig.ledger)(1)
            fail("expected injected delete fault")
        } catch (_: InjectedFault) {
            assertEquals(before.toSet(), rig.entries.rows.value.toSet())
            assertEquals(listOf(debt), rig.debts.stored)
        }
    }

    @Test fun failureAfterClearingDetailsRollsBackTheWholeBatchAndMetadata() = runTest {
        val debt = deletionDebt()
        val rig = SaveEntryRig(initialEntries = listOf(deletionOpening(debt)), initialDebts = listOf(debt), atomic = true)
        val before = rig.entries.rows.value
        val failing = object : DebtRepository by rig.debts {
            override suspend fun updateDetails(id: Long, dueDate: LocalDate?, note: String?) {
                rig.debts.updateDetails(id, dueDate, note)
                throw InjectedFault()
            }
        }
        try {
            DeleteDebt(rig.runner, failing, rig.deletionEntries(), rig.ledger)(1)
            fail("expected metadata fault")
        } catch (_: InjectedFault) {
            assertEquals(before, rig.entries.rows.value)
            assertEquals(listOf(debt), rig.debts.stored)
        }
    }

    @Test fun aFinalGuardFailureRollsBackTheDeletedEntriesAndClearedDetails() = runTest {
        val debt = deletionDebt()
        val rig = SaveEntryRig(initialEntries = listOf(deletionOpening(debt)), initialDebts = listOf(debt), atomic = true)
        rig.retainRawIncome(debt, "-3")
        val before = rig.entries.rows.value
        try {
            DeleteDebt(rig.runner, rig.debts, rig.deletionEntries(), rig.ledger)(1)
            fail("expected negative remaining refusal")
        } catch (failure: WalletException.DebtBelowZero) {
            assertEquals("Ali", failure.counterparty)
            assertEquals(Money.of("3", "USD"), failure.amount)
            assertEquals(before.toSet(), rig.entries.rows.value.toSet())
            assertEquals(listOf(debt), rig.debts.stored)
        }
    }

    @Test(expected = WalletException.DebtNotFound::class)
    fun missingDebtIsReported() = runTest {
        val rig = SaveEntryRig(atomic = true)
        DeleteDebt(rig.runner, rig.debts, rig.deletionEntries(), rig.ledger)(99)
    }
    @Test fun clearingDebtRetainsPaidLaterExpenseAndExactLegs() = runTest {
        val debt = deletionDebt()
        val expense = org.aristonis.mywallet.domain.model.Transaction.Expense(10, 1,
            Money.of("1000", "USD"), 7, date = deletionDay,
            owed = Money.of("700", "USD"), owedDebtId = debt.id)
        val rig = SaveEntryRig(initialEntries = listOf(deletionOpening(debt), expense),
            initialDebts = listOf(debt), atomic = true)
        val before = rig.entries.rows.value.single { it.header.id == expense.id }
        val undo = DeleteDebt(rig.runner, rig.debts, rig.deletionEntries(), rig.ledger)(debt.id)
        assertEquals(listOf(before), rig.entries.rows.value)
        assertEquals(listOf(1L), undo.entries.map { it.id })
        assertEquals(debt.copy(dueDate = null, note = null), rig.debts.stored.single())
    }
}
