package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeLedgerQueries
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Assumed API: `ObserveCategoryUsage(ledger: LedgerQueries)` with `operator fun invoke(): Flow<Map<Long, Int>>`,
 * the ledger's `observeCategoryUsage()` passed through: entries per category id, counting an entry under its
 * main category and under its sub-category, and leaving out categories no entry uses.
 */
class ObserveCategoryUsageTest {

    private val day = LocalDate.of(2026, 7, 10)
    private fun usd(a: String) = Money.of(a, "USD")
    private fun expense(id: Long, category: Long, sub: Long? = null) =
        Transaction.Expense(id = id, accountId = 1, amount = usd("1"), categoryId = category, subCategoryId = sub, date = day)

    private fun usageOf(vararg txs: Transaction) = ObserveCategoryUsage(FakeLedgerQueries(FakeEntryRepository(txs.toList())))

    @Test
    fun noEntriesMeansNoCategoriesInTheMap() = runTest {
        assertEquals(emptyMap<Long, Int>(), usageOf()().first())
    }

    @Test
    fun countsEntriesPerMainCategory() = runTest {
        val usage = usageOf(expense(1, 7), expense(2, 7), expense(3, 9))()

        assertEquals(mapOf(7L to 2, 9L to 1), usage.first())
    }

    @Test
    fun countsAnEntryUnderBothItsMainCategoryAndItsSubCategory() = runTest {
        val usage = usageOf(expense(1, 7, sub = 71), expense(2, 7), expense(3, 7, sub = 71))()

        assertEquals(mapOf(7L to 3, 71L to 2), usage.first())
    }

    @Test
    fun anEntryCountsOnceEvenWhenItHoldsTwoLegsOnTheSameCategory() = runTest {
        val income = Transaction.Income(id = 1, accountId = 1, amount = usd("1"), categoryId = 1, date = day)
        assertEquals(mapOf(1L to 1), usageOf(income)().first())
    }

    @Test
    fun updatesWhenAnEntryIsAdded() = runTest {
        val entries = FakeEntryRepository()
        val usage = ObserveCategoryUsage(FakeLedgerQueries(entries))
        assertEquals(emptyMap<Long, Int>(), usage().first())

        entries.insert(expense(0, 7))

        assertEquals(mapOf(7L to 1), usage().first())
    }
}
