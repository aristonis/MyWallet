package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.RollbackOnFailureRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SaveEntryDeductionsTest {
    @Test fun owedLaterDebtResolutionHappensInsideTheSalaryWriteTransaction() = runTest {
        val rig = salaryRig()
        var calls = 0
        rig.debts.beforeGetOrCreate = {
            assertTrue((rig.runner as RollbackOnFailureRunner).isRunning)
            calls++
        }
        val saved = rig.saveEntry(salary(), emptyMap())
        val stored = rig.entries.findById(saved.id) as Transaction.Income
        assertEquals(2, calls)
        assertTrue(stored.deductions.filter { it.timing == DeductionTiming.LATER }.all { it.debtId != null })
    }

    @Test fun deductionsAboveGrossFailWithTypedErrorBeforeAnyDebtResolution() = runTest {
        val rig = salaryRig()
        var calls = 0
        rig.debts.beforeGetOrCreate = { calls++ }
        val entry = salary().copy(amount = usd("100"))
        val failure = runCatching { rig.saveEntry(entry, emptyMap()) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DeductionsExceedIncome)
        assertEquals(0, calls)
        assertTrue(rig.entries.rows.value.isEmpty())
        assertTrue(rig.debts.stored.isEmpty())
    }

    @Test fun invalidSnapshotPercentageIsATypedRefusalBeforeLegConstruction() = runTest {
        val rig = salaryRig()
        val bad = salaryLine(10, 0, "Tax", 20, "100", timing = DeductionTiming.NOW)
            .copy(mode = DeductionMode.PERCENT, value = BigDecimal("120"), valueCurrencyCode = null)
        val failure = runCatching { rig.saveEntry(salary().copy(deductions = listOf(bad)),
            emptyMap()) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DeductionOutOfRange)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test fun anUnknownDeductionItemRefusesTheSaveWithoutResolvingDebt() = runTest {
        val rig = salaryRig()
        val bad = salaryLine(999, 0, "Tax", 20, "100")
        val failure = runCatching { rig.saveEntry(salary().copy(deductions = listOf(bad)),
            emptyMap()) }.exceptionOrNull()
        assertTrue("was $failure", failure is WalletException.DeductionItemNotFound)
        assertTrue(rig.debts.stored.isEmpty())
        assertTrue(rig.entries.rows.value.isEmpty())
    }
}
