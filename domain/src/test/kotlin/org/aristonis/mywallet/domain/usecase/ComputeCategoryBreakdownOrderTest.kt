package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.CategoryLeg
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeLedgerQueries
import org.aristonis.mywallet.domain.usecase.fake.fakeFx
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Tracking names the currency of the newest entry that has no stored rate. That pick is made here,
 * from each leg's date and entry id, so it must not depend on the order the storage lists the legs
 * in: the read is free to hand them back unsorted.
 */
class ComputeCategoryBreakdownOrderTest {

    private val july = DateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31))
    private val currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2), Currency("GBP", "£", 2))

    private fun expense(id: Long, code: String, date: LocalDate) = Transaction.Expense(
        id = id, accountId = 1, amount = Money.of("10", code), categoryId = 7, date = date, rateToBase = null,
    )

    private val entries = FakeEntryRepository(
        listOf(
            expense(1, "GBP", LocalDate.of(2026, 7, 5)),
            expense(2, "EUR", LocalDate.of(2026, 7, 20)),
            expense(3, "GBP", LocalDate.of(2026, 7, 20).minusDays(1)),
            expense(4, "GBP", LocalDate.of(2026, 7, 2)),
        ),
    )

    private fun breakdownWith(order: (List<CategoryLeg>) -> List<CategoryLeg>): CategoryBreakdownResult {
        val inner: LedgerQueries = FakeLedgerQueries(entries)
        val reordered = object : LedgerQueries by inner {
            override fun observeCategoryLegs(range: DateRange): Flow<List<CategoryLeg>> =
                inner.observeCategoryLegs(range).map(order)
        }
        return runBlocking {
            ComputeCategoryBreakdown(reordered, fakeFx(currencies = currencies, base = "USD")).invoke(july).first()
        }
    }

    @Test
    fun theMissingRateNamesTheNewestEntryWhenLegsComeNewestFirst() = runTest {
        assertEquals(CategoryBreakdownResult.MissingRate("EUR"), breakdownWith { it.sortedByDescending { l -> l.date } })
    }

    @Test
    fun theMissingRateNamesTheNewestEntryWhenLegsComeOldestFirst() = runTest {
        assertEquals(CategoryBreakdownResult.MissingRate("EUR"), breakdownWith { it.sortedBy { l -> l.date } })
    }

    @Test
    fun theMissingRateNamesTheHigherIdWhenTwoEntriesShareTheirDate() = runTest {
        val sameDay = FakeEntryRepository(
            listOf(
                expense(5, "GBP", LocalDate.of(2026, 7, 9)),
                expense(6, "EUR", LocalDate.of(2026, 7, 9)),
            ),
        )
        val inner: LedgerQueries = FakeLedgerQueries(sameDay)
        val unsorted = object : LedgerQueries by inner {
            override fun observeCategoryLegs(range: DateRange): Flow<List<CategoryLeg>> =
                inner.observeCategoryLegs(range).map { it.sortedBy { leg -> leg.entryId } }
        }

        val result = ComputeCategoryBreakdown(unsorted, fakeFx(currencies = currencies, base = "USD")).invoke(july).first()

        assertEquals(CategoryBreakdownResult.MissingRate("EUR"), result)
    }
}
