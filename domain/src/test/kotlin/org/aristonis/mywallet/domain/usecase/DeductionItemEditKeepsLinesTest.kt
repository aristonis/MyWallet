package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.DeductionItem
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.FakeDeductionItemRepository
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.aristonis.mywallet.domain.usecase.fake.deductionItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/**
 * S5-E5 and S5-E15: changing a reusable rule (its default, its name or its expense category) never
 * touches the lines already recorded on a saved income.
 */
class DeductionItemEditKeepsLinesTest {

    private val recordedLine = DeductionLine(
        itemId = ITEM_ID,
        position = 0,
        name = "Tax",
        mode = DeductionMode.PERCENT,
        value = BigDecimal("10"),
        valueCurrencyCode = null,
        timing = DeductionTiming.NOW,
        categoryId = 21,
        amount = Money.of("100", "USD"),
    )
    private val salary = Transaction.Income(
        id = 10,
        accountId = 1,
        amount = Money.of("1000", "USD"),
        categoryId = EntryWorld.incomeCategory.id,
        date = EntryWorld.day,
        deductions = listOf(recordedLine),
    )

    private suspend fun editItemAndReadSalary(edited: DeductionItem): Transaction? {
        val rig = SaveEntryRig(initialEntries = listOf(salary), atomic = true)
        val items = FakeDeductionItemRepository(
            initialItems = listOf(deductionItem(id = ITEM_ID, name = "Tax")),
            usedIds = setOf(ITEM_ID),
        )
        val rowBefore = rig.entries.rows.value

        SaveDeductionItem(items).invoke(edited)

        assertEquals(rowBefore, rig.entries.rows.value)
        assertEquals(salary.toParts().deductionLines, rig.entries.rows.value.single().deductionLines)
        return rig.entries.findById(salary.id)
    }

    @Test fun changingTheDefaultValueTimingAndAutoAddCategoriesLeavesTheRecordedLine() = runTest {
        val edited = deductionItem(
            id = ITEM_ID, name = "Tax", value = "25", timing = DeductionTiming.LATER, autoAddCategoryIds = setOf(5),
        )

        assertEquals(salary, editItemAndReadSalary(edited))
    }

    @Test fun renamingTheItemLeavesTheRecordedLineName() = runTest {
        val saved = editItemAndReadSalary(deductionItem(id = ITEM_ID, name = "Income tax")) as Transaction.Income

        assertEquals("Tax", saved.deductions.single().name)
        assertEquals(salary, saved)
    }

    @Test fun movingTheItemToAnotherExpenseCategoryLeavesTheRecordedLineCategory() = runTest {
        val saved = editItemAndReadSalary(
            deductionItem(id = ITEM_ID, name = "Tax", expenseCategoryId = 99),
        ) as Transaction.Income

        assertEquals(21L, saved.deductions.single().categoryId)
        assertEquals(salary, saved)
    }

    private companion object {
        const val ITEM_ID = 20L
    }
}
