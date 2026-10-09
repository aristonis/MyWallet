package org.aristonis.mywallet.domain.usecase.fake

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Tracking places a category leg by its entry's kind and role, so the read-side projection carries
 * both; a leg whose category row is gone is still projected, under its category id.
 */
class FakeLedgerQueriesTest {

    private val day = LocalDate.of(2026, 5, 1)

    @Test
    fun aCategoryLegCarriesItsEntryKindAndRole() = runTest {
        val entries = FakeEntryRepository(
            listOf(Transaction.Expense(id = 1, accountId = 1, amount = Money.of("5", "USD"), categoryId = 7, date = day)),
        )
        val queries = FakeLedgerQueries(entries)

        val leg = queries.observeCategoryLegs(DateRange(day, day)).first().single()

        assertEquals("EXPENSE", leg.entryKind)
        assertEquals(LegRole.MAIN, leg.role)
    }

    @Test
    fun aLegWhoseCategoryIsGoneIsStillProjected() = runTest {
        val entries = FakeEntryRepository(
            listOf(Transaction.Income(id = 2, accountId = 1, amount = Money.of("5", "USD"), categoryId = 9, date = day)),
        )
        val leg = FakeLedgerQueries(entries).observeCategoryLegs(DateRange(day, day)).first().single()
        assertEquals(9L, leg.categoryId)
        assertEquals("INCOME", leg.entryKind)
    }
}
