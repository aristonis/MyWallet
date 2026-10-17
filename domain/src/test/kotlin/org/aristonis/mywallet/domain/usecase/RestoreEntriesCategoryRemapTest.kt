package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.model.UndoBundle
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Undo of an entry whose category was deleted after the entry was: the leg lands in the Uncategorized
 * bucket of the kind it counts on, as deleting the category would have done to a live entry. Nothing
 * else about the entry changes.
 */
class RestoreEntriesCategoryRemapTest {

    private fun bundleOf(vararg entries: Transaction) = UndoBundle(entries = entries.toList(), debts = emptyList())

    @Test fun aDeductionWhoseSubCategoryWasDeletedKeepsItsParentAndAmount() = runTest {
        val rig = salaryRig()
        val line = salaryLine(12, 0, "Bills", 22, "20", timing = DeductionTiming.NOW)
            .copy(subCategoryId = 77)
        val income = salary().copy(id = 8, deductions = listOf(line))

        rig.restoreEntries(bundleOf(income))

        val restored = rig.entries.findById(8) as Transaction.Income
        assertEquals(income.copy(deductions = listOf(line.copy(subCategoryId = null))), restored)
        assertEquals(LegTarget.Category(22, null),
            rig.entries.rows.value.single().legs.single { it.role == LegRole.DEDUCTION }.target)
    }

    @Test fun aDeductionWhoseCategoryWasDeletedComesBackInTheUncategorizedExpenseBucket() = runTest {
        val rig = salaryRig()
        val id = rig.saveEntry(salary(), emptyMap()).id
        val before = rig.entries.rows.value.single()
        val debtsBefore = rig.debts.stored
        val bundle = rig.deleteEntry(id)
        DeleteCategory(rig.categoryRepository)(20)

        rig.restoreEntries(bundle)

        val bucket = bucket(rig, CategoryKind.EXPENSE)
        val after = rig.entries.rows.value.single()
        assertEquals(before.header, after.header)
        assertEquals(before.legs.map { it.onCategory(LegRole.DEDUCTION, 0, bucket) }, after.legs)
        val restored = rig.entries.findById(id) as Transaction.Income
        val original = bundle.entries.single() as Transaction.Income
        assertEquals(
            original.deductions.map { if (it.position == 0) it.copy(categoryId = bucket) else it },
            restored.deductions,
        )
        assertEquals(debtsBefore, rig.debts.stored)
    }

    @Test fun anIncomeWhoseMainCategoryWasDeletedComesBackInTheUncategorizedIncomeBucket() = runTest {
        val gone = EntryWorld.income(id = 5, rate = "1", categoryId = 77)
        val rig = SaveEntryRig(atomic = true)

        rig.restoreEntries(bundleOf(gone))

        assertEquals(gone.copy(categoryId = bucket(rig, CategoryKind.INCOME)), rig.entries.findById(5))
    }

    @Test fun anExpenseWhoseCategoryWasDeletedComesBackInTheUncategorizedExpenseBucket() = runTest {
        val fine = EntryWorld.income(id = 4, rate = "1")
        val gone = EntryWorld.expense(id = 5, rate = "1", categoryId = 77)
        val rig = SaveEntryRig(atomic = true)

        rig.restoreEntries(bundleOf(fine, gone))

        assertEquals(fine, rig.entries.findById(4))
        assertEquals(gone.copy(categoryId = bucket(rig, CategoryKind.EXPENSE)), rig.entries.findById(5))
    }

    @Test fun aFeeWhoseCategoryWasDeletedComesBackInTheUncategorizedExpenseBucket() = runTest {
        val withFee = EntryWorld.expense(id = 6, rate = "1").copy(fee = usd("2"), feeCategoryId = 70)
        val rig = SaveEntryRig(atomic = true)

        rig.restoreEntries(bundleOf(withFee))

        assertEquals(withFee.copy(feeCategoryId = bucket(rig, CategoryKind.EXPENSE)), rig.entries.findById(6))
    }

    @Test fun aDeletedSubCategoryUnderALiveParentOnlyLosesTheFinerLabel() = runTest {
        val missingSub = EntryWorld.expense(id = 5, rate = "1", subCategoryId = 77)
        val rig = SaveEntryRig(atomic = true)

        rig.restoreEntries(bundleOf(missingSub))

        assertEquals(missingSub.copy(subCategoryId = null), rig.entries.findById(5))
    }

    @Test fun liveCategoriesAreRestoredUntouchedAndNoBucketIsCreated() = runTest {
        val withFee = EntryWorld.expense(id = 6, rate = "1", subCategoryId = EntryWorld.foodSubCategory.id)
            .copy(fee = usd("2"), feeCategoryId = EntryWorld.expenseCategory.id)
        val categories = listOf(EntryWorld.incomeCategory, EntryWorld.expenseCategory, EntryWorld.foodSubCategory)
        val rig = SaveEntryRig(categories = categories, atomic = true)

        rig.restoreEntries(bundleOf(withFee))

        assertEquals(withFee, rig.entries.findById(6))
        assertEquals(categories, rig.categoryRepository.stored)
    }

    private fun bucket(rig: SaveEntryRig, kind: CategoryKind): Long =
        rig.categoryRepository.stored.single { it.systemKey == Category.uncategorizedKeyFor(kind) }.id

    private fun Leg.onCategory(role: LegRole, position: Int, categoryId: Long): Leg =
        if (this.role == role && this.position == position) copy(target = LegTarget.Category(categoryId, null))
        else this
}
