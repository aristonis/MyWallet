package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.DebtDetail
import org.aristonis.mywallet.domain.model.DebtState
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.DebtLeg
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.usecase.fake.FakeDebtRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeLedgerQueries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

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

    @Test fun debtAmountsSeparateAdvancesPrincipalRepaymentsAndWriteOffs() = runTest {
        val debt = queryDebt(1)
        val history = listOf(
            Transaction.Borrow(1, 8, debt.id, Money.of("10.10", "USD"), queryDay),
            opening(debt, "20.20", 2),
            Transaction.CreditPurchase(3, debt.id, Money.of("30.30", "USD"), 1, date = queryDay),
            Transaction.Repayment(4, debt.side, 8, debt.id, Money.of("6.05", "USD"),
                Money.of("5.05", "USD"), Money.of("1.00", "USD"), interestCategoryId = 1,
                date = queryDay),
            Transaction.Repayment(5, debt.side, 8, debt.id, Money.of("2.00", "USD"),
                Money.zero("USD"), Money.of("2.00", "USD"), interestCategoryId = 1,
                date = queryDay),
            Transaction.WriteOff(6, debt.side, debt.id, Money.of("7.07", "USD"), 1, date = queryDay),
        )
        val entries = FakeEntryRepository(history)

        val detail = ObserveDebt(FakeDebtRepository(listOf(debt)), FakeLedgerQueries(entries), entries,
            MutableStateFlow(queryDay))(debt.id).first()!!

        assertEquals(Money.of("60.60", "USD"), detail.amounts.original)
        assertEquals(Money.of("5.05", "USD"), detail.amounts.repaid)
        assertEquals(Money.of("7.07", "USD"), detail.amounts.writtenOff)
        assertEquals(Money.of("48.48", "USD"), detail.summary.remaining)
        assertEquals(detail.amounts.original - detail.amounts.repaid - detail.amounts.writtenOff,
            detail.summary.remaining)

    }

    @Test fun crossCurrencyRepaymentCountsStoredDebtPrincipalRatherThanAccountAmount() = runTest {
        val debt = queryDebt(1)
        val entries = FakeEntryRepository(listOf(
            opening(debt, "20", 1),
            Transaction.Repayment(2, debt.side, 8, debt.id, Money.of("10", "EUR"),
                Money.of("12.50", "USD"), pairRate = BigDecimal("1.25"), date = queryDay),
        ))

        val detail = ObserveDebt(FakeDebtRepository(listOf(debt)), FakeLedgerQueries(entries), entries,
            MutableStateFlow(queryDay))(debt.id).first()!!

        assertEquals(Money.of("20", "USD"), detail.amounts.original)
        assertEquals(Money.of("12.50", "USD"), detail.amounts.repaid)
        assertEquals(Money.of("7.50", "USD"), detail.summary.remaining)
    }

    @Test fun staggeredHistoryAndLegEmissionsMarkTheBreakdownUnavailableUntilTheyAgree() = runTest {
        val debt = queryDebt(1)
        val entries = FakeEntryRepository()
        val legs = MutableStateFlow<List<DebtLeg>>(emptyList())
        val ledger = object : LedgerQueries by FakeLedgerQueries(entries) {
            override fun observeDebtLegs(): kotlinx.coroutines.flow.Flow<List<DebtLeg>> = legs
        }
        val seen = mutableListOf<DebtDetail?>()
        backgroundScope.launch {
            ObserveDebt(FakeDebtRepository(listOf(debt)), ledger, entries,
                MutableStateFlow(queryDay))(debt.id).collect { seen.add(it) }
        }
        runCurrent()
        assertTrue(seen.last()!!.isAmountBreakdownConsistent)

        entries.insert(opening(debt, "5"))
        runCurrent()
        assertEquals(Money.of("5", "USD"), seen.last()!!.amounts.original)
        assertEquals(Money.zero("USD"), seen.last()!!.summary.remaining)
        assertTrue(!seen.last()!!.isAmountBreakdownConsistent)

        legs.value = listOf(DebtLeg(debt.id, debt.side, Money.of("5", "USD")))
        runCurrent()
        assertEquals(Money.of("5", "USD"), seen.last()!!.summary.remaining)
        assertTrue(seen.last()!!.isAmountBreakdownConsistent)
    }

    @Test fun debtAmountsFollowEditedAndDeletedHistoryIncludingNegativeRemaining() = runTest {
        val debt = queryDebt(1, side = org.aristonis.mywallet.domain.model.DebtSide.OWED_TO_ME)
        val entries = FakeEntryRepository(listOf(
            Transaction.Lend(1, 8, debt.id, Money.of("10", "USD"), queryDay),
            opening(debt, "5", 2),
            Transaction.EarnedUnpaid(3, debt.id, Money.of("7", "USD"), 1, date = queryDay),
            Transaction.WriteOff(4, debt.side, debt.id, Money.of("25", "USD"), 1, date = queryDay),
        ))
        val seen = mutableListOf<DebtDetail?>()
        backgroundScope.launch {
            ObserveDebt(FakeDebtRepository(listOf(debt)), FakeLedgerQueries(entries), entries,
                MutableStateFlow(queryDay))(debt.id).collect { seen.add(it) }
        }
        runCurrent()
        assertEquals(Money.of("22", "USD"), seen.last()!!.amounts.original)
        assertEquals(Money.of("25", "USD"), seen.last()!!.amounts.writtenOff)
        assertEquals(Money.of("-3", "USD"), seen.last()!!.summary.remaining)

        entries.replace(Transaction.EarnedUnpaid(3, debt.id, Money.of("9", "USD"), 1, date = queryDay))
        runCurrent()
        assertEquals(Money.of("24", "USD"), seen.last()!!.amounts.original)
        assertEquals(Money.of("-1", "USD"), seen.last()!!.summary.remaining)

        entries.delete(4)
        runCurrent()
        assertEquals(Money.zero("USD"), seen.last()!!.amounts.writtenOff)
        assertEquals(Money.of("24", "USD"), seen.last()!!.summary.remaining)
    }

    @Test fun owedLaterIncomeDeductionCountsAsOriginalOnlyForItsOwnDebt() = runTest {
        val debt = queryDebt(1)
        fun line(position: Int, timing: DeductionTiming, debtId: Long?, amount: String) = DeductionLine(
            itemId = position + 1L, position = position, name = "Item $position", mode = DeductionMode.FIXED,
            value = BigDecimal(amount), valueCurrencyCode = "USD", timing = timing,
            categoryId = 1, amount = Money.of(amount, "USD"), debtId = debtId,
        )
        val income = Transaction.Income(id = 1, accountId = 8, amount = Money.of("100", "USD"),
            categoryId = 1, date = queryDay, deductions = listOf(
                line(0, DeductionTiming.LATER, debt.id, "12"),
                line(1, DeductionTiming.LATER, 2, "3"),
                line(2, DeductionTiming.NOW, null, "2"),
            ))
        val entries = FakeEntryRepository(listOf(income))

        val detail = ObserveDebt(FakeDebtRepository(listOf(debt)), FakeLedgerQueries(entries), entries,
            MutableStateFlow(queryDay))(debt.id).first()!!

        assertEquals(Money.of("12", "USD"), detail.amounts.original)
        assertEquals(Money.of("12", "USD"), detail.summary.remaining)
        assertEquals(detail.amounts.original - detail.amounts.repaid - detail.amounts.writtenOff,
            detail.summary.remaining)

        entries.replace(income.copy(deductions = income.deductions.map { line ->
            if (line.debtId == debt.id) line.copy(value = BigDecimal("15"), amount = Money.of("15", "USD"))
            else line
        }))
        val updated = ObserveDebt(FakeDebtRepository(listOf(debt)), FakeLedgerQueries(entries), entries,
            MutableStateFlow(queryDay))(debt.id).first()!!
        assertEquals(Money.of("15", "USD"), updated.amounts.original)
        assertEquals(Money.of("15", "USD"), updated.summary.remaining)
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
