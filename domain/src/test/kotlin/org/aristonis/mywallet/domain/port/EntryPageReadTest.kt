package org.aristonis.mywallet.domain.port

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.EntryHeader
import org.aristonis.mywallet.domain.ledger.EntryParts
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The paged read of the entry list.
 *
 * Assumed API, which the implementation must provide in `org.aristonis.mywallet.domain.port`:
 * - `data class EntryKey(val date: LocalDate, val id: Long)`, the place of one entry in the list;
 * - `EntryRepository.observePage(range: DateRange, after: EntryKey?, limit: Int): Flow<List<Transaction>>`:
 *   at most [limit] entries dated inside [range], newest first (date desc, then id desc), starting
 *   strictly after the entry named by `after` (null starts at the newest). It re-emits whenever an
 *   entry changes, so a page is always the current rows, never a snapshot.
 *
 * The same cases run against the real database in `RoomEntryRepositoryPageTest`.
 */
class EntryPageReadTest {

    private val july = DateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31))
    private val day = LocalDate.of(2026, 7, 10)

    private fun expense(id: Long, date: LocalDate = day) = Transaction.Expense(
        id = id, accountId = 1, amount = Money.of("5.00", "USD"), categoryId = 2, date = date,
    )

    private fun keyOf(tx: Transaction) = EntryKey(tx.date, tx.id)

    private suspend fun FakeEntryRepository.page(range: DateRange, after: EntryKey?, limit: Int) =
        observePage(range, after, limit).first()

    @Test
    fun theFirstPageIsTheNewestEntriesUpToTheLimit() = runTest {
        val entries = FakeEntryRepository((1L..5L).map { expense(it, day.plusDays(it)) })

        val page = entries.page(DateRange.ALL_TIME, after = null, limit = 3)

        assertEquals(listOf(5L, 4L, 3L), page.map { it.id })
    }

    @Test
    fun theNextPageStartsRightAfterTheKeyWithNoGapAndNoRepeat() = runTest {
        val entries = FakeEntryRepository((1L..7L).map { expense(it, day.plusDays(it)) })
        val first = entries.page(DateRange.ALL_TIME, null, 3)

        val second = entries.page(DateRange.ALL_TIME, keyOf(first.last()), 3)
        val third = entries.page(DateRange.ALL_TIME, keyOf(second.last()), 3)

        assertEquals(listOf(4L, 3L, 2L), second.map { it.id })
        assertEquals(listOf(1L), third.map { it.id })
    }

    @Test
    fun entriesSharingADateAreSplitByIdAcrossAPageBoundary() = runTest {
        val entries = FakeEntryRepository((1L..6L).map { expense(it, day) } + expense(7, day.minusDays(1)))
        val first = entries.page(DateRange.ALL_TIME, null, 4)

        val second = entries.page(DateRange.ALL_TIME, keyOf(first.last()), 4)

        assertEquals(listOf(6L, 5L, 4L, 3L), first.map { it.id })
        assertEquals(listOf(2L, 1L, 7L), second.map { it.id })
    }

    @Test
    fun aKeyWhoseEntryIsGoneStillMarksWhereToContinue() = runTest {
        val entries = FakeEntryRepository((1L..5L).map { expense(it, day) })
        val gone = EntryKey(day, 3)
        entries.delete(3)

        val page = entries.page(DateRange.ALL_TIME, gone, 10)

        assertEquals(listOf(2L, 1L), page.map { it.id })
    }

    @Test
    fun theLastPageIsShorterThanTheLimitAndTheOneAfterItIsEmpty() = runTest {
        val entries = FakeEntryRepository((1L..4L).map { expense(it, day.plusDays(it)) })
        val first = entries.page(DateRange.ALL_TIME, null, 3)
        val last = entries.page(DateRange.ALL_TIME, keyOf(first.last()), 3)

        val beyond = entries.page(DateRange.ALL_TIME, keyOf(last.last()), 3)

        assertEquals(listOf(1L), last.map { it.id })
        assertTrue(beyond.isEmpty())
    }

    @Test
    fun aPageStaysInsideTheRange() = runTest {
        val entries = FakeEntryRepository(
            listOf(
                expense(1, LocalDate.of(2026, 6, 30)),
                expense(2, LocalDate.of(2026, 7, 1)),
                expense(3, LocalDate.of(2026, 7, 31)),
                expense(4, LocalDate.of(2026, 8, 1)),
            ),
        )

        val page = entries.page(july, null, 10)

        assertEquals(listOf(3L, 2L), page.map { it.id })
    }

    @Test
    fun pagesWalkedToTheEndEqualTheWholeRangeRead() = runTest {
        val entries = FakeEntryRepository(
            (1L..23L).map { expense(it, day.plusDays(it % 4)) },
        )
        val walked = mutableListOf<Transaction>()

        var after: EntryKey? = null
        do {
            val page = entries.page(DateRange.ALL_TIME, after, 5)
            walked += page
            after = page.lastOrNull()?.let(::keyOf)
        } while (page.size == 5)

        assertEquals(entries.observeBetween(DateRange.ALL_TIME).first(), walked)
    }

    @Test
    fun aPageReflectsAnEntryAddedAfterItWasRead() = runTest {
        val entries = FakeEntryRepository(listOf(expense(1, day)))
        val before = entries.page(DateRange.ALL_TIME, null, 2)

        entries.insert(expense(0, day.plusDays(1)))
        val after = entries.page(DateRange.ALL_TIME, null, 2)

        assertEquals(listOf(1L), before.map { it.id })
        assertEquals(listOf(2L, 1L), after.map { it.id })
    }

    @Test
    fun aRowOfAKindNoRecipeReadsFailsThePageAsUnreadable() = runTest {
        val entries = FakeEntryRepository(listOf(expense(1, day)))
        entries.seedRaw(EntryParts(EntryHeader(9, "FROM_THE_FUTURE", day.plusDays(1), null), emptyList()))

        val failure = runCatching { entries.page(DateRange.ALL_TIME, null, 5) }.exceptionOrNull()

        assertTrue(failure is WalletException.UnreadableRow)
        assertEquals(9L, (failure as WalletException.UnreadableRow).id)
    }
}
