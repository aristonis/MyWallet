package org.aristonis.mywallet.domain.usecase.fake

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Tracking places a category leg by its category's kind, not by the entry's kind, so the read-side
 * projection carries both; a leg whose category row is gone has no kind and must read as unreadable.
 */
class FakeLedgerQueriesTest {

    private val day = LocalDate.of(2026, 5, 1)

    @Test
    fun aCategoryLegCarriesItsCategoryKindAndItsEntryKind() = runTest {
        val entries = FakeEntryRepository(
            listOf(Transaction.Expense(id = 1, accountId = 1, amount = Money.of("5", "USD"), categoryId = 7, date = day)),
        )
        val queries = FakeLedgerQueries(entries, categoryKinds = mapOf(7L to CategoryKind.EXPENSE))

        val leg = queries.observeCategoryLegs(DateRange(day, day)).first().single()

        assertEquals("EXPENSE", leg.entryKind)
        assertEquals(CategoryKind.EXPENSE, leg.categoryKind)
        assertEquals(LegRole.MAIN, leg.role)
    }

    @Test
    fun aLegWhoseCategoryIsGoneHasNoKind() = runTest {
        val entries = FakeEntryRepository(
            listOf(Transaction.Income(id = 2, accountId = 1, amount = Money.of("5", "USD"), categoryId = 9, date = day)),
        )
        val leg = FakeLedgerQueries(entries, categoryKinds = emptyMap()).observeCategoryLegs(DateRange(day, day)).first().single()
        assertNull(leg.categoryKind)
    }
}
