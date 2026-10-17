package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.fakeFx
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.RollbackOnFailureRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SaveEntryDeductionsTest {
    @Test fun aSalaryUsingALegacyDeductionOnASubCategorySavesUnderItsParentAndSub() = runTest {
        val rig = salaryRig()
        val internet = rig.categoryRepository.upsert(
            Category(name = "Internet", kind = CategoryKind.EXPENSE, parentId = 22),
        )
        val item = requireNotNull(rig.deductions.findById(12))
        rig.deductions.upsert(item.copy(expenseCategoryId = internet))
        val income = salary().copy(deductions = listOf(
            salaryLine(12, 0, "Bills", internet, "20", timing = DeductionTiming.NOW),
        ))

        val saved = rig.saveEntry(income, emptyMap())

        val parts = rig.entries.rows.value.single { it.header.id == saved.id }
        assertEquals(LegTarget.Category(22, internet), parts.legs.single { it.role == LegRole.DEDUCTION }.target)
        assertEquals(usd("980"), parts.legs.single { it.role == LegRole.ACCOUNT }.amount)
    }

    @Test fun aSplitDeductionSavesUnchangedAndTrackingKeepsItsSubCategory() = runTest {
        val rig = salaryRig()
        val internet = rig.categoryRepository.upsert(
            Category(name = "Internet", kind = CategoryKind.EXPENSE, parentId = 22),
        )
        val line = salaryLine(12, 0, "Bills", 22, "20", timing = DeductionTiming.NOW)
            .copy(subCategoryId = internet)

        val saved = rig.saveEntry(salary().copy(deductions = listOf(line)), emptyMap())

        assertEquals(listOf(line), (rig.entries.findById(saved.id) as Transaction.Income).deductions)
        val tracking = ComputeCategoryBreakdown(rig.ledger, fakeFx(EntryWorld.currencies, emptyList(), "USD"))(
            DateRange(EntryWorld.day, EntryWorld.day),
        ).first() as CategoryBreakdownResult.Resolved
        val bills = tracking.breakdown.expense.single { it.categoryId == 22L }
        assertEquals(usd("20"), bills.total)
        assertEquals(internet, bills.subCategories.single().subCategoryId)
    }

    @Test fun aDeductionOnAnIncomeSubCategoryIsRefusedWithoutWriting() = runTest {
        val rig = salaryRig()
        val sub = rig.categoryRepository.upsert(
            Category(name = "Bonus", kind = CategoryKind.INCOME, parentId = EntryWorld.incomeCategory.id),
        )
        val line = salaryLine(12, 0, "Bills", sub, "20", timing = DeductionTiming.NOW)

        val failure = runCatching {
            rig.saveEntry(salary().copy(deductions = listOf(line)), emptyMap())
        }.exceptionOrNull()

        assertTrue("was $failure", failure is WalletException.CategoryWrongKind)
        assertTrue(rig.entries.rows.value.isEmpty())
        assertTrue(rig.debts.stored.isEmpty())
    }

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
