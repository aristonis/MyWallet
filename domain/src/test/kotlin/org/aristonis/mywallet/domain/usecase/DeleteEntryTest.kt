package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class DeleteEntryTest {

    private val transfer = Transaction.Transfer(
        id = 9,
        sourceAccountId = 1,
        destAccountId = 3,
        sourceAmount = Money.of("20", "USD"),
        destAmount = Money.of("20", "USD"),
        rateUsed = BigDecimal.ONE,
        date = EntryWorld.day,
    )

    @Test
    fun deletesTheHeaderAndItsLegs() = runTest {
        val rig = SaveEntryRig(initialEntries = listOf(EntryWorld.expense(id = 4, rate = "1"), transfer))

        rig.deleteEntry(9)

        assertEquals(listOf(4L), rig.entries.rows.value.map { it.header.id })
        assertTrue(rig.entries.rows.value.none { row -> row.legs.any { it.amount == Money.of("20", "USD") } })
    }

    @Test
    fun returnsAnUndoBundleHoldingTheEntryExactly() = runTest {
        val stored = EntryWorld.expense(id = 4, amount = "12.50", rate = "1", note = "lunch")
        val rig = SaveEntryRig(initialEntries = listOf(stored))

        val bundle = rig.deleteEntry(4)

        assertEquals(listOf<Transaction>(stored), bundle.entries)
        assertTrue(bundle.debts.isEmpty())
    }

    @Test
    fun aTransferComesBackInTheBundleWithBothAmounts() = runTest {
        val rig = SaveEntryRig(initialEntries = listOf(transfer))

        val bundle = rig.deleteEntry(9)

        assertEquals(listOf<Transaction>(transfer), bundle.entries)
    }

    @Test
    fun deletingAnUnknownEntryIsRefused() = runTest {
        val rig = SaveEntryRig()

        val failure = runCatching { rig.deleteEntry(404) }.exceptionOrNull()

        assertTrue("was $failure", failure is WalletException.TransactionNotFound)
    }

    @Test
    fun otherEntriesAreLeftAlone() = runTest {
        val other = EntryWorld.expense(id = 5, rate = "1")
        val rig = SaveEntryRig(initialEntries = listOf(EntryWorld.expense(id = 4, rate = "1"), other))

        rig.deleteEntry(4)

        assertEquals(other, rig.entries.findById(5))
    }
}
