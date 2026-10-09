package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.EntryHeader
import org.aristonis.mywallet.domain.ledger.EntryParts
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeLedgerQueries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Assumed API: `ObserveLedgerHealth(ledger: LedgerQueries)` with `operator fun invoke(): Flow<Unit>`.
 * It emits Unit while every stored entry has a recipe and throws `WalletException.UnreadableRow(id, kind)`
 * (the first unknown kind, by id) out of the flow otherwise. The known kinds are the registered recipes,
 * so the stored kind LEGACY_UNREADABLE is never known.
 */
class ObserveLedgerHealthTest {

    private val day = LocalDate.of(2026, 7, 10)
    private fun healthOf(entries: FakeEntryRepository) = ObserveLedgerHealth(FakeLedgerQueries(entries))

    private fun bare(id: Long, kind: String) = EntryParts(EntryHeader(id, kind, day, null), emptyList())

    private suspend fun failureOf(health: ObserveLedgerHealth): Throwable? = runCatching { health().first() }.exceptionOrNull()

    @Test
    fun anEmptyLedgerIsHealthy() = runTest {
        assertEquals(Unit, healthOf(FakeEntryRepository())().first())
    }

    @Test
    fun entriesOfEveryKnownKindAreHealthy() = runTest {
        val entries = FakeEntryRepository(
            listOf(
                Transaction.Income(id = 1, accountId = 1, amount = Money.of("5", "USD"), categoryId = 1, date = day),
                Transaction.Expense(id = 2, accountId = 1, amount = Money.of("2", "USD"), categoryId = 2, date = day),
            ),
        )

        assertEquals(Unit, healthOf(entries)().first())
    }

    @Test
    fun anUnknownKindReportsUnreadableRow() = runTest {
        val entries = FakeEntryRepository()
        entries.seedRaw(bare(7, "FROM_THE_FUTURE"))

        val failure = failureOf(healthOf(entries)) as? WalletException.UnreadableRow

        assertTrue("expected UnreadableRow", failure != null)
        assertEquals(7L, failure!!.id)
        assertEquals("FROM_THE_FUTURE", failure.kind)
    }

    @Test
    fun aLegacyUnreadableEntryReportsUnreadableRow() = runTest {
        val entries = FakeEntryRepository()
        entries.seedRaw(bare(9, "LEGACY_UNREADABLE"))

        val failure = failureOf(healthOf(entries)) as? WalletException.UnreadableRow

        assertTrue("expected UnreadableRow", failure != null)
        assertEquals(9L, failure!!.id)
        assertEquals("LEGACY_UNREADABLE", failure.kind)
    }

    @Test
    fun healthTurnsUnreadableWhenABadEntryArrivesLater() = runTest {
        val entries = FakeEntryRepository()
        val health = healthOf(entries)
        assertEquals(Unit, health().first())

        entries.seedRaw(bare(3, "LEGACY_UNREADABLE"))

        assertTrue(failureOf(health) is WalletException.UnreadableRow)
    }
}
