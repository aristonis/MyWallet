package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.DebtsSnapshot
import org.aristonis.mywallet.domain.model.DebtTotals
import org.aristonis.mywallet.domain.model.DebtState
import org.aristonis.mywallet.domain.model.HomeSummary
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeDebtRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeFxRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeLedgerQueries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveDebtsTest {
    @Test fun hidesDebtsWithoutLegsButKeepsSettledAndNegativeDebtsVisible() = runTest {
        val open = queryDebt(1)
        val settled = queryDebt(2)
        val negative = queryDebt(3, "EUR")
        val entries = FakeEntryRepository(listOf(opening(open, "12"), opening(settled, "10"),
            Transaction.WriteOff(4, settled.side, settled.id, Money.of("10", "USD"), 1, date = queryDay),
            Transaction.WriteOff(5, negative.side, negative.id, Money.of("7", "EUR"), 1, date = queryDay)))
        val snapshot = ObserveDebts(FakeDebtRepository(listOf(open, settled, negative, queryDebt(9))),
            FakeLedgerQueries(entries), FakeFxRepository(queryFx()), MutableStateFlow(queryDay))().first()

        assertEquals(setOf(1L, 2L, 3L), snapshot.debts.map { it.debt.id }.toSet())
        assertEquals(DebtState.SETTLED, snapshot.debts.first { it.debt.id == 2L }.state)
        assertEquals(DebtState.OPEN, snapshot.debts.first { it.debt.id == 3L }.state)
        assertEquals(Money.of("-7", "EUR"), snapshot.debts.first { it.debt.id == 3L }.remaining)
        assertEquals(DebtTotals.Amounts(Money.of("12", "USD"), Money.zero("USD")), snapshot.totals)
    }

    @Test fun sumsPositiveRemainingBySideUsingLiveRates() = runTest {
        val mine = queryDebt(1, "EUR")
        val theirs = queryDebt(2, "USD", DebtSide.OWED_TO_ME)
        val entries = FakeEntryRepository(listOf(opening(mine, "15"), opening(theirs, "8"),
            Transaction.WriteOff(3, mine.side, mine.id, Money.of("5", "EUR"), 1, date = queryDay)))
        val fx = FakeFxRepository(queryFx(mapOf("EUR" to BigDecimal("1.25"))))
        val observe = ObserveDebts(FakeDebtRepository(listOf(mine, theirs)), FakeLedgerQueries(entries), fx, MutableStateFlow(queryDay))

        val seen = mutableListOf<DebtsSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { observe().collect { seen.add(it) } }
        assertEquals(DebtTotals.Amounts(Money.of("12.50", "USD"), Money.of("8", "USD")), seen.last().totals)
        fx.emit(queryFx(mapOf("EUR" to BigDecimal("2"))))
        assertEquals(DebtTotals.Amounts(Money.of("20", "USD"), Money.of("8", "USD")), seen.last().totals)
    }

    @Test fun missingRateAffectsOnlyTheTotalAndKeepsTheRowsReadable() = runTest {
        val debt = queryDebt(1, "EUR")
        val entries = FakeEntryRepository(listOf(opening(debt, "15")))
        val snapshot = ObserveDebts(FakeDebtRepository(listOf(debt)), FakeLedgerQueries(entries),
            FakeFxRepository(queryFx()), MutableStateFlow(queryDay))().first()

        assertEquals(Money.of("15", "EUR"), snapshot.debts.single().remaining)
        assertEquals(DebtTotals.MissingRate("EUR"), snapshot.totals)
    }

    @Test fun emptyWalletHasZeroTotals() = runTest {
        val snapshot = ObserveDebts(FakeDebtRepository(), FakeLedgerQueries(FakeEntryRepository()),
            FakeFxRepository(queryFx()), MutableStateFlow(queryDay))().first()
        assertTrue(snapshot.debts.isEmpty())
        assertEquals(DebtTotals.Amounts(Money.zero("USD"), Money.zero("USD")), snapshot.totals)
    }

    @Test fun midnightChangesAnUnpaidDebtFromOpenToOverdue() = runTest {
        val debt = queryDebt(1, due = queryDay)
        val today = MutableStateFlow(queryDay)
        val observe = ObserveDebts(FakeDebtRepository(listOf(debt)), FakeLedgerQueries(FakeEntryRepository(listOf(opening(debt, "5")))),
            FakeFxRepository(queryFx()), today)
        val seen = mutableListOf<DebtsSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { observe().collect { seen.add(it) } }
        assertEquals(DebtState.OPEN, seen.last().debts.single().state)
        today.value = queryDay.plusDays(1)
        assertEquals(DebtState.OVERDUE, seen.last().debts.single().state)
    }
    @Test fun missingRateRecoversOnTheSameCollectorAfterTheRateIsSet() = runTest {
        val debt = queryDebt(1, "EUR")
        val fx = FakeFxRepository(queryFx())
        val observe = ObserveDebts(FakeDebtRepository(listOf(debt)),
            FakeLedgerQueries(FakeEntryRepository(listOf(opening(debt, "10")))), fx, MutableStateFlow(queryDay))
        val seen = mutableListOf<DebtsSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { observe().collect { seen.add(it) } }
        assertEquals(DebtTotals.MissingRate("EUR"), seen.last().totals)
        fx.emit(queryFx(mapOf("EUR" to BigDecimal("1.25"))))
        assertEquals(DebtTotals.Amounts(Money.of("12.50", "USD"), Money.zero("USD")), seen.last().totals)
    }

    @Test fun debtTotalsEqualHomesDebtTotalsForTheSameLegs() = runTest {
        val mine = queryDebt(1, "EUR")
        val theirs = queryDebt(2, "USD", DebtSide.OWED_TO_ME)
        val negative = queryDebt(3, "JPY")
        val entries = FakeEntryRepository(listOf(opening(mine, "15"), opening(theirs, "8"),
            Transaction.WriteOff(4, mine.side, mine.id, Money.of("5", "EUR"), 1, date = queryDay),
            Transaction.WriteOff(5, negative.side, negative.id, Money.of("3", "JPY"), 1, date = queryDay)))
        val ledger = FakeLedgerQueries(entries)
        val fx = FakeFxRepository(queryFx(mapOf("EUR" to BigDecimal("1.2345"))))
        val totals = ObserveDebts(FakeDebtRepository(listOf(mine, theirs, negative)), ledger, fx,
            MutableStateFlow(queryDay))().first().totals as DebtTotals.Amounts
        val home = ComputeHomeSummary(ledger, fx)(flowOf(emptyList())).first() as HomeSummary.Amounts
        assertEquals(home.iOwe, totals.iOwe)
        assertEquals(home.owedToMe, totals.owedToMe)
    }

}
