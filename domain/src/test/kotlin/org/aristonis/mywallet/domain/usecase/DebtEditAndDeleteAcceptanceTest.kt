package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

class DebtEditAndDeleteAcceptanceTest {
    @Test fun refusingAnOversizedRepaymentEditKeepsHistoryThenWholeDebtDeleteAndUndoRestoreIt() = runTest {
        val debt = deletionDebt()
        val opening = deletionOpening(debt)
        val paid = deletionPayment(debt)
        val rig = SaveEntryRig(initialEntries = listOf(opening, paid), initialDebts = listOf(debt), atomic = true)
        try {
            rig.saveEntry(paid.copy(accountAmount = Money.of("11", "USD"), principal = Money.of("11", "USD")), emptyMap())
            fail("expected debt below zero")
        } catch (failure: WalletException.DebtBelowZero) {
            assertEquals("Ali", failure.counterparty)
            assertEquals(Money.of("1", "USD"), failure.amount)
            assertEquals(paid, rig.entries.findById(2))
            assertEquals(debt, rig.debts.stored.single())
        }
        val preview = PreviewDebtDeletion(rig.runner, rig.debts, rig.deletionEntries(), rig.ledger)(1)
        assertEquals(setOf(opening, paid), preview.entries.toSet())
        val undo = DeleteDebt(rig.runner, rig.debts, rig.deletionEntries(), rig.ledger)(1)
        assertTrue(rig.entries.rows.value.isEmpty())
        rig.restoreEntries(undo)
        assertEquals(opening, rig.entries.findById(1))
        assertEquals(paid, rig.entries.findById(2))
        assertEquals(debt, rig.debts.stored.single())
    }

    @Test fun undoKeepsOriginalIdsCategoryRatesDateAndDetailsExactly() = runTest {
        val debt = deletionDebt(code = "EUR")
        val purchase = Transaction.CreditPurchase(40, debt.id, Money.of("10.0001", "EUR"), 6,
            date = deletionDay.minusDays(20), note = "original receipt", rateToBase = BigDecimal("1.12345678"))
        val rig = SaveEntryRig(initialEntries = listOf(purchase), initialDebts = listOf(debt), atomic = true)
        val undo = DeleteDebt(rig.runner, rig.debts, rig.deletionEntries(), rig.ledger)(1)
        rig.restoreEntries(undo)
        assertEquals(purchase, rig.entries.findById(40))
        assertEquals(debt, rig.debts.stored.single())
    }
}
