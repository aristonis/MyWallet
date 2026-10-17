package org.aristonis.mywallet.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterQueryBatchesTest {
    @Test fun emptyGroupsProduceOneUnrestrictedQuery() {
        val batches = filterQueryBatches(emptyList(), emptyList(), emptyList())
        assertEquals(1, batches.size)
        assertTrue(batches.single().typeGroups.isEmpty())
        assertTrue(batches.single().accountIds.isEmpty())
        assertTrue(batches.single().categoryIds.isEmpty())
    }

    @Test fun everyQueryFitsOldSqliteAndEverySelectionPairIsCovered() {
        val types = listOf("INCOME", "EXPENSE", "TRANSFER", "DEBTS")
        val accounts = (1L..650L).toList()
        val categories = (1001L..1700L).toList()
        val batches = filterQueryBatches(types, accounts, categories)
        assertTrue(batches.size > 1)
        batches.forEach { batch ->
            assertEquals(types, batch.typeGroups)
            // Includes repeated SQL parameters and a conservative fixed-parameter allowance.
            assertTrue(16 + 4 * batch.typeGroups.size + batch.accountIds.size +
                2 * batch.categoryIds.size <= 999)
        }
        val pairs = batches.flatMap { batch -> batch.accountIds.flatMap { account ->
            batch.categoryIds.map { category -> account to category }
        } }
        assertEquals(accounts.size * categories.size, pairs.size)
        assertEquals(pairs.size, pairs.toSet().size)
        assertEquals(accounts.toSet(), batches.flatMap { it.accountIds }.toSet())
        assertEquals(categories.toSet(), batches.flatMap { it.categoryIds }.toSet())
    }

    @Test fun oneUnrestrictedGroupStaysUnrestrictedAcrossBatches() {
        val categories = (1L..500L).toList()
        val byCategory = filterQueryBatches(listOf("EXPENSE"), emptyList(), categories)
        assertTrue(byCategory.size > 1)
        assertTrue(byCategory.all { it.accountIds.isEmpty() })
        assertEquals(categories, byCategory.flatMap { it.categoryIds })
        val byAccount = filterQueryBatches(emptyList(), categories, emptyList())
        assertTrue(byAccount.all { it.categoryIds.isEmpty() && it.typeGroups.isEmpty() })
        assertEquals(categories, byAccount.flatMap { it.accountIds })
    }
    @Test fun mergedHeadersDeduplicateAndKeepGlobalDateThenIdOrderBeforeLimit() {
        val day = java.time.LocalDate.of(2026, 10, 1)
        fun header(id: Long, offset: Long = 0) = EntryEntity(id, "EXPENSE", day.plusDays(offset))
        val rows = listOf(header(1), header(9), header(5, 1), header(9), header(3, 1))
        assertEquals(listOf(5L, 3L, 9L), mergeFilteredHeaders(rows, 3).map { it.id })
        assertTrue(mergeFilteredHeaders(emptyList(), 3).isEmpty())
    }

}
