package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.RepaymentTestRig.Companion.day
import org.aristonis.mywallet.domain.usecase.RepaymentTestRig.Companion.opening
import org.aristonis.mywallet.domain.usecase.RepaymentTestRig.Companion.usd
import org.aristonis.mywallet.domain.usecase.fake.DebtEntryWorld
import org.aristonis.mywallet.domain.usecase.fake.InjectedFault
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class WriteOffDebtTest {
    @Test fun writingOffWhatWasOwedToMeTracksTheExactRemainingAsExpenseWithoutAccountMovement() = runTest {
        val rig = RepaymentTestRig(initialEntries = listOf(opening(debtId = 8, side = DebtSide.OWED_TO_ME, amount = "300")))
        val saved = rig.writeOff(debtId = 8, categoryId = 6, date = day, note = "uncollectable")
        val writtenOff = rig.store.entries.findById(saved.id) as Transaction.WriteOff
        assertEquals(DebtSide.OWED_TO_ME, writtenOff.side)
        assertEquals(usd("300"), writtenOff.amount)
        assertEquals("uncollectable", writtenOff.note)
        assertEquals(usd("0"), rig.remaining(8))
        assertTrue(rig.store.ledger.observeAccountLegs().first().isEmpty())
        assertEquals(usd("300"), rig.store.ledger.observeCategoryLegs(DateRange.ALL_TIME).first().single().amount)
    }

    @Test fun writingOffWhatIOweTracksTheExactRemainingAsIncomeWithoutAccountMovement() = runTest {
        val rig = RepaymentTestRig(initialEntries = listOf(opening(amount = "300")))
        val saved = rig.writeOff(7, 5, day)
        val writtenOff = rig.store.entries.findById(saved.id) as Transaction.WriteOff
        assertEquals(DebtSide.I_OWE, writtenOff.side)
        assertEquals(usd("300"), writtenOff.amount)
        assertEquals(5L, writtenOff.categoryId)
        assertEquals(usd("0"), rig.remaining())
        assertTrue(rig.store.ledger.observeAccountLegs().first().isEmpty())
        assertEquals(5L, rig.store.ledger.observeCategoryLegs(DateRange.ALL_TIME).first().single().categoryId)
    }

    @Test fun writingOffAfterAPartialRepaymentUsesTheCurrentRest() = runTest {
        val rig = RepaymentTestRig()
        rig.record(7, 1, usd("400"), day)
        val saved = rig.writeOff(7, 5, day)
        assertEquals(usd("4600"), (rig.store.entries.findById(saved.id) as Transaction.WriteOff).amount)
        assertEquals(usd("0"), rig.remaining())
    }

    @Test fun aSettledDebtCannotBeWrittenOffAgain() = runTest {
        val rig = RepaymentTestRig()
        rig.record(7, 1, null, day)
        val failure = runCatching { rig.writeOff(7, 5, day) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtHasNoRemaining)
        assertEquals("Ali", (failure as WalletException.DebtHasNoRemaining).counterparty)
        assertEquals(2, rig.store.entries.rows.value.size)
    }

    @Test fun aDebtWithNoEntriesCannotBeWrittenOff() = runTest {
        val rig = RepaymentTestRig(initialEntries = emptyList())
        val failure = runCatching { rig.writeOff(7, 5, day) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtHasNoRemaining)
        assertTrue(rig.store.entries.rows.value.isEmpty())
    }

    @Test fun aNegativeDebtIsRefusedInsteadOfWritingOffANegativeAmount() = runTest {
        val paid = Transaction.Repayment(id = 2, side = DebtSide.I_OWE, accountId = 1, debtId = 7,
            accountAmount = usd("100"), principal = usd("100"), date = day)
        val rig = RepaymentTestRig(initialEntries = listOf(opening(amount = "50"), paid))
        val failure = runCatching { rig.writeOff(7, 5, day) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
        assertEquals("Ali", (failure as WalletException.DebtBelowZero).counterparty)
        assertEquals(usd("50"), failure.amount)
        assertEquals(2, rig.store.entries.rows.value.size)
    }

    @Test fun aMissingDebtIsATypedRefusal() = runTest {
        val rig = RepaymentTestRig()
        val failure = runCatching { rig.writeOff(99, 5, day) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DebtNotFound)
        assertEquals(1, rig.store.entries.rows.value.size)
    }

    @Test fun aDeletedChosenCategoryIsRefused() = runTest {
        val rig = RepaymentTestRig()
        val failure = runCatching { rig.writeOff(7, 99, day) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.CategoryNotFound)
        assertEquals(usd("5000"), rig.remaining())
        assertEquals(1, rig.store.entries.rows.value.size)
    }

    @Test fun writingOffALiabilityRequiresAnIncomeCategory() = runTest {
        val rig = RepaymentTestRig()
        val failure = runCatching { rig.writeOff(7, 6, day) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.CategoryWrongKind)
        assertEquals(usd("5000"), rig.remaining())
    }

    @Test fun writingOffAReceivableRequiresAnExpenseCategory() = runTest {
        val rig = RepaymentTestRig(initialEntries = listOf(opening(debtId = 8, side = DebtSide.OWED_TO_ME)))
        val failure = runCatching { rig.writeOff(8, 5, day) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.CategoryWrongKind)
        assertEquals(usd("5000"), rig.remaining(8))
    }

    @Test fun writingOffRequiresAPositiveChosenCategoryId() = runTest {
        val rig = RepaymentTestRig()
        val categoriesBefore = rig.store.categoryRepository.stored
        val failure = runCatching { rig.writeOff(7, 0, day) }.exceptionOrNull()
        assertTrue("was $failure", failure is IllegalArgumentException)
        assertEquals(categoriesBefore, rig.store.categoryRepository.stored)
        assertEquals(1, rig.store.entries.rows.value.size)
    }

    @Test fun aForeignCurrencyWriteOffKeepsItsOwnAmountAndStampedRate() = runTest {
        val debt = DebtEntryWorld.ali.copy(currencyCode = "EUR")
        val rig = RepaymentTestRig(debts = listOf(debt), initialEntries = listOf(opening(amount = "300", currency = "EUR")),
            savedRates = mapOf("EUR" to "1.1"))
        val saved = rig.writeOff(7, 5, day)
        val writtenOff = rig.store.entries.findById(saved.id) as Transaction.WriteOff
        assertEquals(Money.of("300", "EUR"), writtenOff.amount)
        assertEquals(0, BigDecimal("1.1").compareTo(writtenOff.rateToBase))
        assertEquals(Money.zero("EUR"), rig.remaining())
    }

    @Test fun deletingAWriteOffReopensTheSameRemainingAndUndoSettlesItAgain() = runTest {
        val detailed = DebtEntryWorld.sara.copy(dueDate = day.plusDays(7), note = "keep details")
        val rig = RepaymentTestRig(debts = listOf(detailed),
            initialEntries = listOf(opening(debtId = 8, side = DebtSide.OWED_TO_ME, amount = "300")))
        val saved = rig.writeOff(8, 6, day)
        val before = rig.store.entries.findById(saved.id)
        val bundle = rig.store.deleteEntry(saved.id)
        assertEquals(usd("300"), rig.remaining(8))
        assertEquals(detailed, rig.store.debts.findById(8))
        rig.store.restoreEntries(bundle)
        assertEquals(before, rig.store.entries.findById(saved.id))
        assertEquals(usd("0"), rig.remaining(8))
        assertEquals(detailed, rig.store.debts.findById(8))
    }

    @Test fun aLateRateFillFailureLeavesTheDebtAndDetailsAndRatesAsTheyWere() = runTest {
        val detailed = DebtEntryWorld.ali.copy(dueDate = day.plusDays(7), note = "keep details")
        val rig = RepaymentTestRig(debts = listOf(detailed), faultAfterFill = true)
        val failure = runCatching { rig.writeOff(7, 5, day, typedRates = mapOf("SYP" to BigDecimal("0.0002"))) }.exceptionOrNull()
        assertTrue("was $failure", failure is InjectedFault)
        assertEquals(usd("5000"), rig.remaining())
        assertEquals(detailed, rig.store.debts.findById(7))
        assertEquals(1, rig.store.entries.rows.value.size)
        assertNull(rig.store.rates.findByCode("SYP"))
    }
}
