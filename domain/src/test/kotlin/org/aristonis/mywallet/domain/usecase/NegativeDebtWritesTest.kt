package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.model.UndoBundle
import org.aristonis.mywallet.domain.service.DebtRemaining
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld
import org.aristonis.mywallet.domain.usecase.fake.InjectedFault
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NegativeDebtWritesTest {
    @Test fun increasingAnExistingOpeningMayImproveANegativeDebtWithoutReachingZero() = runTest {
        DebtSide.entries.forEach { side ->
            val rig = negativeRig(side)
            rig.saveEntry(opening(side, "110"), emptyMap())
            assertEquals(Money.of("-10", "USD"), remaining(rig))
        }
    }

    @Test fun aNoteAndDateEditMayLeaveAnExistingNegativeRemainingUnchanged() = runTest {
        DebtSide.entries.forEach { side ->
            val rig = negativeRig(side)
            val payment = payment(side, "120").copy(note = "corrected receipt", date = EntryWorld.day.minusDays(1))
            rig.saveEntry(payment, emptyMap())
            assertEquals(payment, rig.entries.findById(2))
            assertEquals(Money.of("-20", "USD"), remaining(rig))
        }
    }

    @Test fun anInsertionMayPartlyRepairANegativeDebt() = runTest {
        val rig = negativeRig(DebtSide.I_OWE)
        rig.saveEntry(opening(DebtSide.I_OWE, "5").copy(id = 0), emptyMap())
        assertEquals(Money.of("-15", "USD"), remaining(rig))
    }

    @Test fun aSaveThatMakesANegativeDebtWorseRollsBackTheEntry() = runTest {
        val rig = negativeRig(DebtSide.I_OWE)
        val failure = runCatching { rig.saveEntry(opening(DebtSide.I_OWE, "90"), emptyMap()) }.exceptionOrNull()
        assertTrue(failure is WalletException.DebtBelowZero)
        assertEquals(opening(DebtSide.I_OWE, "100"), rig.entries.findById(1))
        assertEquals(Money.of("-20", "USD"), remaining(rig))
    }

    @Test fun deletingOnePaymentMayImproveANegativeDebtWithoutReachingZero() = runTest {
        DebtSide.entries.forEach { side ->
            val rig = negativeRig(side, extraPayment = true)
            val removed = rig.deleteEntry(3)
            assertEquals(listOf(payment(side, "10", 3)), removed.entries)
            assertNull(rig.entries.findById(3))
            assertEquals(Money.of("-10", "USD"), remaining(rig))
        }
    }

    @Test fun deletingAnOpeningThatWorsensTheNegativeDebtIsAtomic() = runTest {
        val rig = negativeRig(DebtSide.I_OWE)
        val failure = runCatching { rig.deleteEntry(1) }.exceptionOrNull()
        assertTrue(failure is WalletException.DebtBelowZero)
        assertEquals(opening(DebtSide.I_OWE, "100"), rig.entries.findById(1))
        assertEquals(Money.of("-20", "USD"), remaining(rig))
    }

    @Test fun undoMayPartlyRepairANegativeDebt() = runTest {
        DebtSide.entries.forEach { side ->
            val rig = negativeRig(side)
            val restored = opening(side, "5").copy(id = 3)
            rig.restoreEntries(UndoBundle(listOf(restored)))
            assertEquals(restored, rig.entries.findById(3))
            assertEquals(Money.of("-15", "USD"), remaining(rig))
        }
    }

    @Test fun undoThatWorsensANegativeDebtRollsBackEntriesAndRestoredMetadata() = runTest {
        val rig = negativeRig(DebtSide.I_OWE)
        val snapshot = debt(DebtSide.I_OWE).copy(dueDate = EntryWorld.day, note = "previous agreement")
        val failure = runCatching {
            rig.restoreEntries(UndoBundle(listOf(payment(DebtSide.I_OWE, "5", 3)), listOf(snapshot)))
        }.exceptionOrNull()
        assertTrue(failure is WalletException.DebtBelowZero)
        assertNull(rig.entries.findById(3))
        assertEquals(debt(DebtSide.I_OWE), rig.debts.findById(7))
        assertEquals(Money.of("-20", "USD"), remaining(rig))
    }

    @Test fun wholeDebtDeletionMayLeaveAnUnchangedNegativeRetainedRawLeg() = runTest {
        val person = debt(DebtSide.I_OWE)
        val rig = SaveEntryRig(initialDebts = listOf(person), atomic = true)
        rig.retainRawIncome(person, amount = "-20")
        val bundle = DeleteDebt(rig.runner, rig.debts, rig.deletionEntries(), rig.ledger)(person.id)
        assertTrue(bundle.entries.isEmpty())
        assertEquals(listOf(person), bundle.debts)
        assertEquals(Money.of("-20", "USD"), remaining(rig))
        assertEquals(1, rig.entries.rows.value.size)
    }

    @Test fun aLateRateFailureRollsBackAPartialNegativeDebtRepair() = runTest {
        val side = DebtSide.I_OWE
        val rig = SaveEntryRig(initialDebts = listOf(debt(side)),
            initialEntries = listOf(opening(side, "100"), payment(side, "120")),
            savedRates = mapOf("USD" to "13000"), base = "SYP", faultAfterFill = true, atomic = true)
        val failure = runCatching {
            rig.saveEntry(opening(side, "5").copy(id = 0), mapOf("USD" to java.math.BigDecimal("15000")))
        }.exceptionOrNull()
        assertTrue("was $failure", failure is InjectedFault)
        assertEquals(Money.of("-20", "USD"), remaining(rig))
        assertEquals(2, rig.entries.rows.value.size)
        assertEquals(java.math.BigDecimal("13000"), rig.rates.observeAll().first().single().rateToBase)
    }

    @Test fun metadataOnlyUndoMayLeaveAnExistingNegativeRemainingUnchanged() = runTest {
        val rig = negativeRig(DebtSide.I_OWE)
        val snapshot = debt(DebtSide.I_OWE).copy(dueDate = EntryWorld.day, note = "agreement")
        rig.restoreEntries(UndoBundle(emptyList(), listOf(snapshot)))
        assertEquals(snapshot, rig.debts.findById(7))
        assertEquals(Money.of("-20", "USD"), remaining(rig))
    }

    private fun debt(side: DebtSide): Debt = DebtEntryWorld.ali.copy(side = side)
    private fun opening(side: DebtSide, amount: String) =
        Transaction.OwedBefore(1, 7, side, Money.of(amount, "USD"), EntryWorld.day)
    private fun payment(side: DebtSide, amount: String, id: Long = 2) =
        DebtEntryWorld.repayment(id, 7, side, amount)
    private fun negativeRig(side: DebtSide, extraPayment: Boolean = false): SaveEntryRig {
        val entries = listOf(opening(side, "100"), payment(side, if (extraPayment) "110" else "120")) +
            if (extraPayment) listOf(payment(side, "10", 3)) else emptyList()
        return SaveEntryRig(initialDebts = listOf(debt(side)), initialEntries = entries, atomic = true)
    }
    private suspend fun remaining(rig: SaveEntryRig): Money =
        DebtRemaining.byDebt(rig.ledger.debtLegs(setOf(7))).getValue(7).remaining
}
