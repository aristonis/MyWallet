package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.usecase.fake.FakeDeductionItemRepository
import org.aristonis.mywallet.domain.usecase.fake.deductionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProposeDeductionsTest {

    @Test
    fun incomeCategorySelectsItsEnabledAutoAddItemsAndOffersOtherEnabledItems() = runTest {
        val salaryTax = deduction(id = 1, name = "Tax", autoAddCategoryIds = setOf(SALARY))
        val manualFee = deduction(id = 2, name = "Agent fee")
        val repository = FakeDeductionItemRepository(listOf(salaryTax, manualFee))

        val proposals = ProposeDeductions(repository).invoke(SALARY)

        assertEquals(setOf(1L, 2L), proposals.map { it.item.id }.toSet())
        assertTrue(proposals.single { it.item.id == 1L }.selectedByDefault)
        assertFalse("enabled non-default items remain available for manual addition",
            proposals.single { it.item.id == 2L }.selectedByDefault)
    }

    @Test
    fun categoryWithoutAutoAddItemsHasNoDefaultSelectionsButOffersEnabledItems() = runTest {
        val repository = FakeDeductionItemRepository(
            listOf(deduction(id = 3, name = "Tax", autoAddCategoryIds = setOf(SALARY))),
        )

        val proposals = ProposeDeductions(repository).invoke(FREELANCE)

        assertEquals(1, proposals.size)
        assertEquals(3L, proposals.single().item.id)
        assertFalse(proposals.single().selectedByDefault)
    }

    @Test
    fun disabledItemIsNeitherSelectedNorOffered() = runTest {
        val enabledAuto = deduction(id = 4, name = "Tax", autoAddCategoryIds = setOf(SALARY))
        val disabledAuto = deduction(
            id = 5,
            name = "Family share",
            autoAddCategoryIds = setOf(SALARY),
            enabled = false,
        )
        val disabledManual = deduction(id = 6, name = "Old fee", enabled = false)
        val repository = FakeDeductionItemRepository(listOf(enabledAuto, disabledAuto, disabledManual))

        val proposals = ProposeDeductions(repository).invoke(SALARY)

        assertEquals(listOf(4L), proposals.map { it.item.id })
        assertTrue(proposals.single().selectedByDefault)
    }

    private companion object {
        const val SALARY = 10L
        const val FREELANCE = 11L

        fun deduction(
            id: Long,
            name: String,
            autoAddCategoryIds: Set<Long> = emptySet(),
            enabled: Boolean = true,
        ) = deductionItem(
            id = id,
            name = name,
            value = "2",
            timing = DeductionTiming.NOW,
            autoAddCategoryIds = autoAddCategoryIds,
            enabled = enabled,
        )
    }
}
