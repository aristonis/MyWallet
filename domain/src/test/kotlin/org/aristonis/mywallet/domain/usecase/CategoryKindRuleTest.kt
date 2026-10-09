package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** The category on an entry's main leg must be of the kind that leg counts as. */
class CategoryKindRuleTest {

    @Test
    fun anIncomeCategoryOnAnExpenseIsRefusedAndNothingIsSaved() = runTest {
        val rig = SaveEntryRig()

        val failure = runCatching {
            rig.saveEntry(EntryWorld.expense(categoryId = EntryWorld.incomeCategory.id), typedRates = emptyMap())
        }.exceptionOrNull()

        assertTrue("was $failure", failure is WalletException.CategoryWrongKind)
        assertEquals(EntryWorld.incomeCategory.id, (failure as WalletException.CategoryWrongKind).categoryId)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test
    fun anExpenseCategoryOnAnIncomeIsRefusedAndNothingIsSaved() = runTest {
        val rig = SaveEntryRig()

        val failure = runCatching {
            rig.saveEntry(EntryWorld.income(categoryId = EntryWorld.expenseCategory.id), typedRates = emptyMap())
        }.exceptionOrNull()

        assertTrue("was $failure", failure is WalletException.CategoryWrongKind)
        assertEquals(EntryWorld.expenseCategory.id, (failure as WalletException.CategoryWrongKind).categoryId)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test
    fun aRefusedEntryKeepsNoTypedRate() = runTest {
        val rig = SaveEntryRig(saveRates = true)

        runCatching {
            rig.saveEntry(
                EntryWorld.expense(accountId = 2, amount = "5", currency = "SYP", categoryId = EntryWorld.incomeCategory.id),
                typedRates = mapOf("SYP" to BigDecimal("0.0002")),
            )
        }

        assertNull(rig.rates.findByCode("SYP"))
    }

    @Test
    fun aWrongKindEditLeavesTheStoredEntryAsItWas() = runTest {
        val original = EntryWorld.expense(id = 3, rate = "1", note = "keep")
        val rig = SaveEntryRig(initialEntries = listOf(original))

        runCatching {
            rig.saveEntry(
                EntryWorld.expense(id = 3, rate = "1", note = "changed", categoryId = EntryWorld.incomeCategory.id),
                typedRates = emptyMap(),
            )
        }

        assertEquals(original, rig.entries.findById(3))
    }

    @Test
    fun theMatchingKindIsAccepted() = runTest {
        val rig = SaveEntryRig()

        rig.saveEntry(EntryWorld.expense(), typedRates = emptyMap())
        rig.saveEntry(EntryWorld.income(), typedRates = emptyMap())

        assertEquals(2, rig.entries.rows.value.size)
    }
}
