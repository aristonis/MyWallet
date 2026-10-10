package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.DebtDetail
import org.aristonis.mywallet.domain.model.DebtState
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeDebtRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeLedgerQueries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveDebtTest {
    @Test fun returnsNullForAMissingDebt() = runTest {
        val entries = FakeEntryRepository()
        assertNull(ObserveDebt(FakeDebtRepository(), FakeLedgerQueries(entries), entries, MutableStateFlow(queryDay))(99).first())
    }

    @Test fun aHiddenDebtRemainsReachableWithEmptyHistory() = runTest {
        val debt = queryDebt(1)
        val entries = FakeEntryRepository()
        val detail = ObserveDebt(FakeDebtRepository(listOf(debt)), FakeLedgerQueries(entries), entries, MutableStateFlow(queryDay))(1).first()!!
        assertEquals(DebtState.HIDDEN, detail.summary.state)
        assertEquals(Money.zero("USD"), detail.summary.remaining)
        assertTrue(detail.entries.isEmpty())
    }

    @Test fun keepsANegativeDebtAndItsCompleteNewestFirstHistoryReachable() = runTest {
        val debt = queryDebt(1)
        val first = opening(debt, "5", 1)
        val newer = Transaction.WriteOff(3, debt.side, 1, Money.of("7", "USD"), 1, date = queryDay.plusDays(1))
        val tied = Transaction.WriteOff(4, debt.side, 1, Money.of("1", "USD"), 1, date = queryDay.plusDays(1))
        val entries = FakeEntryRepository(listOf(first, opening(queryDebt(2), "99", 2), newer, tied))
        val detail = ObserveDebt(FakeDebtRepository(listOf(debt)), FakeLedgerQueries(entries), entries, MutableStateFlow(queryDay))(1).first()!!
        assertEquals(DebtState.OPEN, detail.summary.state)
        assertEquals(Money.of("-3", "USD"), detail.summary.remaining)
        assertEquals(listOf(tied, newer, first), detail.entries)
    }

    @Test fun detailUsesTheDebtHistoryReadRatherThanLoadingEveryEntry() = runTest {
        val debt = queryDebt(1)
        val entries = FakeEntryRepository(listOf(opening(debt, "5")))
        val narrow = object : org.aristonis.mywallet.domain.port.EntryRepository by entries {
            override fun observeAll(): kotlinx.coroutines.flow.Flow<List<Transaction>> = error("whole history read")
        }
        val detail = ObserveDebt(FakeDebtRepository(listOf(debt)), FakeLedgerQueries(entries), narrow, MutableStateFlow(queryDay))(1).first()!!
        assertEquals(listOf(1L), detail.entries.map { it.id })
    }
    @Test fun theSameSubscriberReceivesMetadataMidnightAndHistoryUpdates() = runTest {
        val debt = queryDebt(1)
        val debts = FakeDebtRepository(listOf(debt))
        val entries = FakeEntryRepository(listOf(opening(debt, "5")))
        val today = MutableStateFlow(queryDay)
        val seen = mutableListOf<DebtDetail?>()
        backgroundScope.launch {
            ObserveDebt(debts, FakeLedgerQueries(entries), entries, today)(1).collect { seen.add(it) }
        }
        runCurrent()
        assertEquals(DebtState.OPEN, seen.last()!!.summary.state)
        assertEquals(Money.of("5", "USD"), seen.last()!!.summary.remaining)

        debts.rename(1, "Ali")
        runCurrent()
        assertEquals("Ali", seen.last()!!.summary.debt.counterparty)

        debts.updateDetails(1, queryDay, "rent")
        runCurrent()
        assertEquals(queryDay, seen.last()!!.summary.debt.dueDate)
        assertEquals("rent", seen.last()!!.summary.debt.note)
        assertEquals(DebtState.OPEN, seen.last()!!.summary.state)

        today.value = queryDay.plusDays(1)
        runCurrent()
        assertEquals(DebtState.OVERDUE, seen.last()!!.summary.state)

        val insertedId = entries.insert(opening(debt, "3", 2))
        runCurrent()
        assertEquals(Money.of("8", "USD"), seen.last()!!.summary.remaining)
        assertEquals(listOf(insertedId, 1L), seen.last()!!.entries.map { it.id })
        assertEquals("Ali", seen.last()!!.summary.debt.counterparty)
        assertEquals("rent", seen.last()!!.summary.debt.note)
    }

}
