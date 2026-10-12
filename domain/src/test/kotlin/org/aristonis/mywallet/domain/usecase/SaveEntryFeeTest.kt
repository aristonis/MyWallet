package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.CategoryRole
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveEntryFeeTest {
    @Test fun anUnpickedExpenseFeeUsesTheUncategorizedExpenseFallback() = runTest {
        val rig = SaveEntryRig(atomic = true)
        val saved = rig.saveEntry(EntryWorld.expense(amount = "50").copy(fee = usd("1")), emptyMap())
        val stored = rig.entries.findById(saved.id) as Transaction.Expense
        val bucket = rig.categoryRepository.stored.single { it.systemKey != null }
        assertEquals(CategoryKind.EXPENSE, bucket.kind)
        assertEquals(bucket.id, stored.feeCategoryId)
        assertEquals(usd("-51"), rig.entries.rows.value.single().legs.single { it.role == LegRole.ACCOUNT }.amount)
    }

    @Test fun aPickedFeeCategoryWinsOverTheFeesRole() = runTest {
        val rig = SaveEntryRig(roleBindings = mapOf(CategoryRole.FEES to 99), atomic = true)
        val saved = rig.saveEntry(EntryWorld.expense().copy(fee = usd("1"), feeCategoryId = 6), emptyMap())
        assertEquals(6L, (rig.entries.findById(saved.id) as Transaction.Expense).feeCategoryId)
        assertEquals(99L, rig.roles.stored[CategoryRole.FEES])
    }

    @Test fun sending300WithFee6Delivers294AndDeletingItRemovesItsFee() = runTest {
        val rig = SaveEntryRig(atomic = true)
        val saved = rig.saveEntry(EntryWorld.transfer(amount = "300").copy(fee = usd("6")), emptyMap())
        val stored = rig.entries.findById(saved.id) as Transaction.Transfer
        assertEquals(usd("300"), stored.sourceAmount)
        assertEquals(usd("294"), stored.destAmount)
        assertEquals(usd("6"), stored.fee)
        assertEquals(1, rig.entries.rows.value.single().legs.count { it.role == LegRole.FEE })
        rig.deleteEntry(saved.id)
        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test fun anExpenseFeeMayEqualOrExceedItsPrincipal() = runTest {
        for (fee in listOf("50", "60")) {
            val rig = SaveEntryRig(atomic = true)
            val saved = rig.saveEntry(EntryWorld.expense(amount = "50").copy(fee = usd(fee),
                feeCategoryId = 6), emptyMap())
            val stored = rig.entries.findById(saved.id) as Transaction.Expense
            assertEquals(usd(fee), stored.fee)
            assertEquals(-(usd("50") + usd(fee)),
                rig.entries.rows.value.single().legs.single { it.role == LegRole.ACCOUNT }.amount)
        }
    }

    @Test fun aTransferFeeAtOrAboveSourceIsATypedRefusalWithoutWrites() = runTest {
        for (fee in listOf("300", "301")) {
            val rig = SaveEntryRig(atomic = true)
            val failure = runCatching {
                rig.saveEntry(EntryWorld.transfer(amount = "300").copy(fee = usd(fee), feeCategoryId = 6), emptyMap())
            }.exceptionOrNull()
            assertTrue("was $failure", failure is WalletException.FeeNotBelowAmount)
            assertTrue(rig.entries.rows.value.isEmpty())
        }
    }

    @Test fun zeroExpenseFeeAndAnUnusedPickLeaveNoFeeOrFallback() = runTest {
        val rig = SaveEntryRig(atomic = true)
        val categories = rig.categoryRepository.stored
        val saved = rig.saveEntry(EntryWorld.expense().copy(fee = usd("0"), feeCategoryId = 6), emptyMap())
        val stored = rig.entries.findById(saved.id) as Transaction.Expense
        assertNull(stored.fee)
        assertNull(stored.feeCategoryId)
        assertEquals(categories, rig.categoryRepository.stored)
    }

    @Test fun zeroFeeNormalizesToNoFeeWithoutCreatingAFallbackBucket() = runTest {
        for (picked in listOf<Long?>(null, 6)) {
            val rig = SaveEntryRig(atomic = true)
            val before = rig.categoryRepository.stored
            val saved = rig.saveEntry(EntryWorld.transfer(amount = "300").copy(fee = usd("0"),
                feeCategoryId = picked), emptyMap())
            val stored = rig.entries.findById(saved.id) as Transaction.Transfer
            assertNull(stored.fee)
            assertNull(stored.feeCategoryId)
            assertEquals(usd("300"), stored.destAmount)
            assertFalse(rig.entries.rows.value.single().legs.any { it.role == LegRole.FEE })
            assertEquals(before, rig.categoryRepository.stored)
        }
    }
}
