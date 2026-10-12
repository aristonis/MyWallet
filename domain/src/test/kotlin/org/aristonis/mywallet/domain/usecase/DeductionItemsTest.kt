package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.usecase.fake.FakeDeductionItemRepository
import org.aristonis.mywallet.domain.usecase.fake.deductionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class DeductionItemsTest {

    @Test
    fun savesTrimmedEnabledItemAndListsIt() = runTest {
        val repository = FakeDeductionItemRepository()
        val id = SaveDeductionItem(repository).invoke(deductionItem(name = "  Tax  "))

        val saved = ObserveDeductionItems(repository).invoke().first().single()
        assertEquals(id, saved.id)
        assertEquals("Tax", saved.name)
        assertTrue(saved.enabled)
    }

    @Test
    fun savingNameMadeOnlyOfInvisibleCharactersFailsBeforeStorage() = runTest {
        val repository = FakeDeductionItemRepository()

        failureOf<WalletException.NameBlank> {
            SaveDeductionItem(repository).invoke(deductionItem(name = " \u200B "))
        }

        assertTrue(repository.observeAll().first().isEmpty())
    }

    @Test
    fun updatesAnExistingItemAndCanDisableIt() = runTest {
        val repository = FakeDeductionItemRepository(listOf(deductionItem(id = 4, name = "Tax")))

        SaveDeductionItem(repository).invoke(deductionItem(id = 4, name = "Income tax", value = "12.5"))
        SetDeductionItemEnabled(repository).invoke(4, enabled = false)

        val saved = repository.findById(4)!!
        assertEquals("Income tax", saved.name)
        assertEquals(0, BigDecimal("12.5").compareTo(saved.value))
        assertFalse(saved.enabled)
    }

    @Test
    fun savingAnItemWhoseIdNoLongerExistsFailsWithTypedNotFoundAndStoresNothing() = runTest {
        val repository = FakeDeductionItemRepository()

        val failure = failureOf<WalletException.DeductionItemNotFound> {
            SaveDeductionItem(repository).invoke(deductionItem(id = 99, name = "Tax"))
        }

        assertEquals(99L, failure.id)
        assertTrue(repository.observeAll().first().isEmpty())
    }

    @Test
    fun deletesAnUnusedItem() = runTest {
        val repository = FakeDeductionItemRepository(listOf(deductionItem(id = 7, name = "Agent fee")))

        DeleteDeductionItem(repository).invoke(7)

        assertTrue(repository.observeAll().first().isEmpty())
    }

    @Test
    fun usedItemDeletionIsRefusedWithTypedErrorAndPreservesItem() = runTest {
        val repository = FakeDeductionItemRepository(
            initialItems = listOf(deductionItem(id = 8, name = "Tax")),
            usedIds = setOf(8),
        )

        val failure = failureOf<WalletException.DeductionItemInUse> { DeleteDeductionItem(repository).invoke(8) }

        assertEquals(8L, failure.id)
        assertEquals("Tax", repository.findById(8)?.name)
    }

    @Test
    fun deletingAMissingItemFailsWithTypedNotFound() = runTest {
        val failure = failureOf<WalletException.DeductionItemNotFound> {
            DeleteDeductionItem(FakeDeductionItemRepository()).invoke(42)
        }

        assertEquals(42L, failure.id)
    }

    @Test
    fun togglingAMissingItemFailsWithTypedNotFound() = runTest {
        val repository = FakeDeductionItemRepository()

        val failure = failureOf<WalletException.DeductionItemNotFound> {
            SetDeductionItemEnabled(repository).invoke(43, enabled = false)
        }

        assertEquals(43L, failure.id)
    }

    /** Runs a suspending call and returns the typed failure it must throw. */
    private inline fun <reified T : Throwable> failureOf(crossinline call: suspend () -> Unit): T =
        assertThrows(T::class.java) { runBlocking { call() } }
}
