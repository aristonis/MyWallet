package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.model.DebtRef
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SaveEntrySnapshotBoundaryTest {
    @Test fun editingAfterRuleAndDebtRenameKeepsHistoricalSnapshotAndResolvedDebt() = runTest {
        val rig = salaryRig()
        val id = rig.saveEntry(salary(), emptyMap()).id
        val stored = rig.entries.findById(id) as Transaction.Income
        val tax = stored.deductions.first()
        rig.debts.rename(tax.debtId!!, "Tax authority")
        val item = rig.deductions.findById(tax.itemId)!!
        rig.deductions.upsert(item.copy(name = "New rule", value = BigDecimal("30"), expenseCategoryId = 22))
        rig.debts.beforeGetOrCreate = { error("editing a resolved deduction must not create a debt") }

        rig.saveEntry(stored.copy(note = "edited"), emptyMap())

        assertEquals(stored.deductions, (rig.entries.findById(id) as Transaction.Income).deductions)
        assertEquals(2, rig.debts.stored.size)
        assertEquals("Tax authority", rig.debts.findById(tax.debtId!!)!!.counterparty)
    }

    @Test fun missingIncomeRateIsStoredAsMissingAndUndoKeepsIt() = runTest {
        val rig = salaryRig()
        val id = rig.saveEntry(salary("SYP", 2), emptyMap()).id
        val stored = rig.entries.findById(id) as Transaction.Income
        assertNull(stored.rateToBase)
        assertTrue(rig.entries.rows.value.single().legs.filter {
            it.role == LegRole.MAIN || it.role == LegRole.DEDUCTION
        }.all { it.rateToBase == null })

        rig.restoreEntries(rig.deleteEntry(id))

        assertEquals(stored, rig.entries.findById(id))
    }

    @Test fun editingIncomeKeepsHistoricalRatesOnMainAndDeductionLegs() = runTest {
        val rig = salaryRig(savedRates = mapOf("SYP" to "0.002"))
        val id = rig.saveEntry(salary("SYP", 2), mapOf("SYP" to BigDecimal("0.001"))).id
        val stored = rig.entries.findById(id) as Transaction.Income

        rig.saveEntry(stored.copy(note = "edited"), emptyMap())

        val categoryLegs = rig.entries.rows.value.single().legs.filter {
            it.role == LegRole.MAIN || it.role == LegRole.DEDUCTION
        }
        assertTrue(categoryLegs.isNotEmpty())
        assertTrue(categoryLegs.all { it.rateToBase?.compareTo(BigDecimal("0.001")) == 0 })
    }

    @Test fun unknownFixedCurrencyAndMismatchedAmountsAreRefusedBeforeResolvingDebts() = runTest {
        for (bad in listOf(
            salary().deductions.first().copy(valueCurrencyCode = "ZZZ"),
            salary().deductions.first().copy(amount = Money.of("100", "SYP")),
        )) {
            val rig = salaryRig()
            rig.debts.beforeGetOrCreate = { error("invalid snapshot must fail before resolving a debt") }
            val failure = runCatching {
                rig.saveEntry(salary().copy(deductions = listOf(bad)), emptyMap())
            }.exceptionOrNull()
            assertTrue("was $failure", failure is WalletException.CurrencyNotFound ||
                failure is WalletException.CurrencyMismatch)
            assertTrue(rig.entries.rows.value.isEmpty())
        }
    }

    @Test fun aFixedForeignValueKeepsTheExplicitIncomeCurrencyAmountWithoutAForeignRate() = runTest {
        val rig = salaryRig()
        val line = salary().deductions.first().copy(valueCurrencyCode = "EUR", value = BigDecimal("200"))

        val id = rig.saveEntry(salary().copy(deductions = listOf(line)), emptyMap()).id

        val stored = (rig.entries.findById(id) as Transaction.Income).deductions.single()
        assertEquals(BigDecimal("200"), stored.value)
        assertEquals("EUR", stored.valueCurrencyCode)
        assertEquals(usd("100"), stored.amount)
    }

    @Test fun invalidSnapshotValueAndCurrencyShapeFailBeforeDebtResolution() = runTest {
        for (bad in listOf(
            salary().deductions.first().copy(value = BigDecimal.ZERO),
            salary().deductions.first().copy(valueCurrencyCode = null),
            salary().deductions.first().copy(mode = DeductionMode.PERCENT, valueCurrencyCode = "USD"),
            salary().deductions.first().copy(amount = usd("-1")),
            salary().deductions.first().copy(position = 2),
        )) {
            val rig = salaryRig()
            rig.debts.beforeGetOrCreate = { error("invalid snapshot must fail before resolving a debt") }
            val failure = runCatching {
                rig.saveEntry(salary().copy(deductions = listOf(bad)), emptyMap())
            }.exceptionOrNull()
            assertTrue("was $failure", failure is WalletException.DeductionValueNotPositive ||
                failure is WalletException.DeductionSnapshotInvalid)
            assertTrue(rig.entries.rows.value.isEmpty())
        }
    }

    @Test fun paidNowSnapshotCannotCarryADebtReference() = runTest {
        val rig = salaryRig()
        val line = salary().deductions.first().copy(timing = DeductionTiming.NOW, debtId = 99)
        val failure = runCatching {
            rig.saveEntry(salary().copy(deductions = listOf(line)), emptyMap())
        }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DeductionSnapshotInvalid)
        assertTrue(rig.debts.stored.isEmpty())
    }

    @Test fun aNegativeFeeIsTypedAndLeavesNoFallbackOrEntry() = runTest {
        val rig = SaveEntryRig(atomic = true)
        val before = rig.categoryRepository.stored
        val failure = runCatching {
            rig.saveEntry(EntryWorld.expense().copy(fee = usd("-1")), emptyMap())
        }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.FeeValueNegative)
        assertEquals(before, rig.categoryRepository.stored)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test fun deductionDebtsUseTheirOwnSnapshotNamesRatherThanTheSingleEntryDebtReference() = runTest {
        val rig = salaryRig()

        rig.saveEntry(salary(), emptyMap(), DebtRef("Unrelated", DebtSide.OWED_TO_ME, "EUR"))

        assertEquals(setOf("Tax", "Family"), rig.debts.stored.map { it.counterparty }.toSet())
        assertTrue(rig.debts.stored.all { it.side == DebtSide.I_OWE && it.currencyCode == "USD" })
    }

    @Test fun anEditCarryingADebtIdThatNoLongerExistsRefusesBeforeWrites() = runTest {
        val rig = salaryRig()
        val id = rig.saveEntry(salary(), emptyMap()).id
        val stored = rig.entries.findById(id) as Transaction.Income
        val taxId = stored.deductions.first().debtId!!
        rig.debts.restoreSnapshot(rig.debts.stored.filterNot { it.id == taxId })
        val before = rig.entries.rows.value
        rig.debts.beforeGetOrCreate = { error("a missing debt must fail before resolving another") }

        val failure = runCatching { rig.saveEntry(stored.copy(note = "edited"), emptyMap()) }.exceptionOrNull()

        assertTrue("was $failure", failure is WalletException.DebtNotFound)
        assertEquals(before, rig.entries.rows.value)
    }
}
