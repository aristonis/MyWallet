package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.DeductionInput
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.service.CurrencyConverter
import org.aristonis.mywallet.domain.service.DebtRemaining
import org.aristonis.mywallet.domain.service.DeductionCalculator
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.aristonis.mywallet.domain.usecase.fake.deductionItem
import org.aristonis.mywallet.domain.usecase.fake.fakeFx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SalaryRoundingSaveTest {
    @Test fun aPaidNowPercentageThatRoundsToZeroKeepsItsSnapshotAndFullReceipt() = runTest {
        val rig = rig(DeductionTiming.NOW)
        val draft = calculatedIncome(DeductionTiming.NOW)

        val id = rig.saveEntry(draft, emptyMap()).id

        assertZeroDeductionSaved(rig, id, draft)
    }

    @Test fun anOwedLaterPercentageThatRoundsToZeroKeepsItsSnapshotWithoutCreatingDebt() = runTest {
        val rig = rig(DeductionTiming.LATER)
        val draft = calculatedIncome(DeductionTiming.LATER)
        var debtResolutions = 0
        rig.debts.beforeGetOrCreate = { debtResolutions++ }

        val id = rig.saveEntry(draft, emptyMap()).id

        assertZeroDeductionSaved(rig, id, draft)
        assertEquals(0, debtResolutions)
        assertTrue(rig.debts.stored.isEmpty())
    }

    @Test fun anUnpaidDeductionEditedToRoundedZeroClearsItsOldDebtReference() = runTest {
        val rig = rig(DeductionTiming.LATER)
        val id = rig.saveEntry(calculatedIncome(DeductionTiming.LATER, "1"), emptyMap()).id
        val old = rig.entries.findById(id) as Transaction.Income
        val oldDebtId = requireNotNull(old.deductions.single().debtId)
        val debtRows = rig.debts.stored
        val calculated = calculatedIncome(DeductionTiming.LATER)
        val zeroDraft = calculated.copy(id = id,
            deductions = calculated.deductions.map { it.copy(debtId = oldDebtId) },
        )

        rig.saveEntry(zeroDraft, emptyMap())

        val stored = rig.entries.findById(id) as Transaction.Income
        assertEquals(usd("0"), stored.deductions.single().amount)
        assertNull(stored.deductions.single().debtId)
        assertFalse(rig.entries.rows.value.single().legs.any { it.role == LegRole.DEDUCTION_DEBT })
        assertEquals(debtRows, rig.debts.stored)
        assertEquals(usd("0"), rig.ledger.debtLegs(setOf(oldDebtId)).fold(usd("0")) { total,
            leg -> total + leg.amount })
    }

    @Test fun aPaidDeductionEditedToRoundedZeroRefusesAndPreservesTheOriginalAndPayment() = runTest {
        val rig = rig(DeductionTiming.LATER)
        val id = rig.saveEntry(calculatedIncome(DeductionTiming.LATER, "1"), emptyMap()).id
        val old = rig.entries.findById(id) as Transaction.Income
        val debtId = requireNotNull(old.deductions.single().debtId)
        rig.saveEntry(Transaction.Repayment(
            side = DebtSide.I_OWE, accountId = 1, debtId = debtId,
            accountAmount = usd("0.01"), principal = usd("0.01"),
            date = EntryWorld.day,
        ), emptyMap())
        val before = rig.entries.rows.value
        val debts = rig.debts.stored
        val calculated = calculatedIncome(DeductionTiming.LATER)
        val zeroDraft = calculated.copy(id = id,
            deductions = calculated.deductions.map { it.copy(debtId = debtId) },
        )

        val failure = runCatching { rig.saveEntry(zeroDraft, emptyMap()) }.exceptionOrNull()

        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
        assertEquals(before, rig.entries.rows.value)
        assertEquals(old, rig.entries.findById(id))
        assertEquals(debts, rig.debts.stored)
        assertEquals(usd("0"), DebtRemaining.byDebt(rig.ledger.debtLegs(setOf(debtId))).getValue(debtId).remaining)
    }

    @Test fun movingAnUnpaidRoundedZeroDeductionToEuroClearsTheOldDollarDebtReference() = runTest {
        val rig = euroRig()
        val id = rig.saveEntry(calculatedIncome(DeductionTiming.LATER, "1"), emptyMap()).id
        val old = rig.entries.findById(id) as Transaction.Income
        val oldDebtId = requireNotNull(old.deductions.single().debtId)
        val debts = rig.debts.stored
        val euro = calculatedIncome(DeductionTiming.LATER, currency = "EUR", accountId = 4)
        val edit = euro.copy(id = id, deductions = euro.deductions.map { it.copy(debtId = oldDebtId) })

        rig.saveEntry(edit, emptyMap())

        val stored = rig.entries.findById(id) as Transaction.Income
        assertEquals(Money.of("0.01", "EUR"), stored.amount)
        assertEquals(Money.zero("EUR"), stored.deductions.single().amount)
        assertNull(stored.deductions.single().debtId)
        assertFalse(rig.entries.rows.value.single().legs.any { it.role == LegRole.DEDUCTION_DEBT })
        assertEquals(debts, rig.debts.stored)
        val oldDebtTotal = rig.ledger.debtLegs(setOf(oldDebtId)).fold(usd("0")) { total, leg -> total + leg.amount }
        assertEquals(usd("0"), oldDebtTotal)
    }

    @Test fun movingAPaidRoundedZeroDeductionToEuroRefusesByOldDebtBalanceAtomically() = runTest {
        val rig = euroRig()
        val id = rig.saveEntry(calculatedIncome(DeductionTiming.LATER, "1"), emptyMap()).id
        val old = rig.entries.findById(id) as Transaction.Income
        val debtId = requireNotNull(old.deductions.single().debtId)
        rig.saveEntry(payment(debtId, "0.01"), emptyMap())
        val rows = rig.entries.rows.value
        val debts = rig.debts.stored
        val euro = calculatedIncome(DeductionTiming.LATER, currency = "EUR", accountId = 4)
        val edit = euro.copy(id = id, deductions = euro.deductions.map { it.copy(debtId = debtId) })

        val failure = runCatching { rig.saveEntry(edit, emptyMap()) }.exceptionOrNull()

        assertTrue("was $failure", failure is WalletException.DebtBelowZero)
        assertEquals(rows, rig.entries.rows.value)
        assertEquals(old, rig.entries.findById(id))
        assertEquals(debts, rig.debts.stored)
        assertEquals(usd("0"), DebtRemaining.byDebt(rig.ledger.debtLegs(setOf(debtId))).getValue(debtId).remaining)
    }

    private fun euroRig() = SaveEntryRig(
        accounts = listOf(EntryWorld.usdAccount, EntryWorld.account(4, "EUR")),
        initialDeductionItems = listOf(deductionItem(
            id = 10, name = "Tax", value = "1", timing = DeductionTiming.LATER, expenseCategoryId = 6,
        )),
        atomic = true,
    )

    private fun calculatedIncome(timing: DeductionTiming, gross: String = "0.01",
        currency: String = "USD", accountId: Long = 1): Transaction.Income {
        val input = DeductionInput(DeductionMode.PERCENT, BigDecimal.ONE, null, timing)
        val calculation = DeductionCalculator.calculate(
            gross = Money.of(gross, currency), deductions = listOf(input),
            converter = CurrencyConverter(currency, emptyMap(), mapOf(currency to 2)),
        )
        assertEquals(Money.of(if (gross == "1") "0.01" else "0.00", currency), calculation.lines.single().amount)
        val snapshot = DeductionLine(
            itemId = 10, position = 0, name = "Tax", mode = input.mode, value = input.value,
            valueCurrencyCode = input.valueCurrencyCode, timing = input.timing,
            categoryId = 6, amount = calculation.lines.single().amount,
        )
        return EntryWorld.income(amount = gross, currency = currency, accountId = accountId)
            .copy(deductions = listOf(snapshot))
    }

    private fun rig(timing: DeductionTiming) = SaveEntryRig(
        initialDeductionItems = listOf(deductionItem(
            id = 10, name = "Tax", value = "1", timing = timing, expenseCategoryId = 6,
        )),
        atomic = true,
    )

    private suspend fun assertZeroDeductionSaved(rig: SaveEntryRig, id: Long, draft: Transaction.Income) {
        val stored = rig.entries.findById(id) as Transaction.Income
        assertEquals(draft.deductions, stored.deductions)
        assertNull(stored.deductions.single().debtId)
        val parts = rig.entries.rows.value.single()
        assertEquals(usd("0.01"), parts.legs.single { it.role == LegRole.ACCOUNT }.amount)
        assertFalse(parts.legs.any { it.role == LegRole.DEDUCTION_DEBT })
        val summary = (ComputeCategoryBreakdown(
            rig.ledger, fakeFx(EntryWorld.currencies, emptyList(), "USD"),
        )(DateRange.ALL_TIME).first() as CategoryBreakdownResult.Resolved).breakdown.summary
        assertEquals(usd("0.01"), summary.income)
        assertEquals(usd("0"), summary.expense)
        assertEquals(usd("0.01"), summary.net)
        val bundle = rig.deleteEntry(id)
        rig.restoreEntries(bundle)
        assertEquals(stored, rig.entries.findById(id))
    }
}
