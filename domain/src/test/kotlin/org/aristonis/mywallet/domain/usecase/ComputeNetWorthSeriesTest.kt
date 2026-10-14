package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.HomeSummary
import org.aristonis.mywallet.domain.model.MAX_CHART_LEGS
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.NetWorthPoint
import org.aristonis.mywallet.domain.model.NetWorthSeries
import org.aristonis.mywallet.domain.model.NoTrendReason
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.FakeAccountRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeLedgerQueries
import org.aristonis.mywallet.domain.usecase.fake.FakeNetWorthLegReader
import org.aristonis.mywallet.domain.usecase.fake.fakeFx
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The series restates the ledger as it is now: each point is today's balance minus the legs dated
 * after that day, under Home's inclusion and sign rules at one current rate snapshot. Every value
 * is an exact decimal sum, so the expected figures below are plain arithmetic on the entries.
 */
class ComputeNetWorthSeriesTest {

    private val today = LocalDate.of(2026, 7, 15)
    private val currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2))

    private fun usd(a: String) = Money.of(a, "USD")
    private fun eur(a: String) = Money.of(a, "EUR")
    private fun on(month: Int, day: Int) = LocalDate.of(2026, month, day)

    private fun cash(id: Long, opening: String, currency: String = "USD", archived: Boolean = false) = Account(
        id = id, name = "Cash $id", typeKey = "cash", currencyCode = currency,
        openingBalance = Money.of(opening, currency), archived = archived,
    )

    private fun card(id: Long, opening: String) = Account(
        id = id, name = "Card $id", typeKey = "credit_card", currencyCode = "USD",
        openingBalance = usd(opening),
    )

    private fun expense(day: LocalDate, amount: String, account: Long = 1, currency: String = "USD") =
        Transaction.Expense(accountId = account, amount = Money.of(amount, currency), categoryId = 1, date = day)

    private fun income(day: LocalDate, amount: String, account: Long = 1) =
        Transaction.Income(accountId = account, amount = usd(amount), categoryId = 2, date = day)

    private class Setup(val series: ComputeNetWorthSeries, val balances: Flow<List<org.aristonis.mywallet.domain.model.AccountBalance>>, val reader: FakeNetWorthLegReader, val home: ComputeHomeSummary)

    private fun setup(
        accounts: List<Account>,
        entries: List<Transaction> = emptyList(),
        rates: List<ExchangeRate> = emptyList(),
        maxLegs: Int = MAX_CHART_LEGS,
    ): Setup {
        val repo = FakeEntryRepository(entries)
        val ledger = FakeLedgerQueries(repo)
        val reader = FakeNetWorthLegReader(repo)
        val fx = fakeFx(currencies, rates, "USD")
        val balances = GetAccountBalances(FakeAccountRepository(accounts), ledger)()
        return Setup(ComputeNetWorthSeries(ledger, reader, fx, maxLegs), balances, reader, ComputeHomeSummary(ledger, fx))
    }

    private suspend fun Setup.run(range: DateRange): NetWorthSeries = series(balances, range, today).first()

    private fun points(vararg pairs: Pair<LocalDate, String>) =
        NetWorthSeries.Trend(pairs.map { (d, a) -> NetWorthPoint(d, usd(a)) })

    @Test
    fun aShortWindowHasOnePointPerDayAndPastPointsTakeAwayLaterLegsIncludingFutureOnes() = runTest {
        val s = setup(
            listOf(cash(1, "1000")),
            listOf(expense(on(7, 10), "100"), income(on(7, 12), "50"), expense(on(7, 20), "30")),
        )

        val result = s.run(DateRange(on(7, 10), on(7, 15)))

        assertEquals(
            points(
                on(7, 10) to "900", on(7, 11) to "900", on(7, 12) to "950",
                on(7, 13) to "950", on(7, 14) to "950", on(7, 15) to "950",
            ),
            result,
        )
    }

    @Test
    fun theLastPointOnTodayEqualsHomesBalanceNowExceptForFutureDatedEntries() = runTest {
        val s = setup(listOf(cash(1, "1000")), listOf(expense(on(7, 10), "100"), income(on(7, 12), "50")))

        val result = s.run(DateRange(on(7, 10), on(7, 15))) as NetWorthSeries.Trend
        val homeBalance = (s.home(s.balances).first() as HomeSummary.Amounts).balance

        assertEquals(homeBalance, result.points.last().balance)
    }

    @Test
    fun aLongWindowHasCalendarMonthEndsAndTheSelectedEnd() = runTest {
        val s = setup(listOf(cash(1, "1000")), listOf(expense(on(5, 5), "10"), expense(on(6, 10), "20")))

        val result = s.run(DateRange(on(4, 20), on(7, 15)))

        assertEquals(
            points(on(4, 30) to "1000", on(5, 31) to "990", on(6, 30) to "970", on(7, 15) to "970"),
            result,
        )
    }

    @Test
    fun aWindowEndingAfterTodayStopsAtToday() = runTest {
        val s = setup(listOf(cash(1, "1000")), listOf(expense(on(7, 5), "10")))

        val result = s.run(DateRange(on(7, 1), on(7, 31))) as NetWorthSeries.Trend

        assertEquals(on(7, 1), result.points.first().date)
        assertEquals(today, result.points.last().date)
        assertEquals(15, result.points.size)
    }

    @Test
    fun aStartOlderThanTwelveMonthsGivesTheCurrentBalanceAndReadsNoLegs() = runTest {
        val s = setup(listOf(cash(1, "1000")), listOf(expense(on(7, 10), "100")))

        val result = s.run(DateRange(LocalDate.of(2025, 7, 14), today))

        assertEquals(NetWorthSeries.CurrentOnly(usd("900"), NoTrendReason.START_TOO_OLD), result)
        assertEquals(0, s.reader.readCount)
    }

    @Test
    fun aStartExactlyTwelveMonthsBackStillDrawsAndAllTimeDoesNot() = runTest {
        val s = setup(listOf(cash(1, "1000")))

        val edge = s.run(DateRange(LocalDate.of(2025, 7, 15), today))
        val all = s.run(DateRange.ALL_TIME)

        assertEquals(true, edge is NetWorthSeries.Trend)
        assertEquals(NetWorthSeries.CurrentOnly(usd("1000"), NoTrendReason.START_TOO_OLD), all)
    }

    @Test
    fun fewerThanTwoPointsGivesTheCurrentBalanceAndReadsNoLegs() = runTest {
        val s = setup(listOf(cash(1, "1000")))

        val onToday = s.run(DateRange(today, today))
        val afterToday = s.run(DateRange(on(8, 1), on(8, 31)))

        assertEquals(NetWorthSeries.CurrentOnly(usd("1000"), NoTrendReason.TOO_FEW_POINTS), onToday)
        assertEquals(NetWorthSeries.CurrentOnly(usd("1000"), NoTrendReason.TOO_FEW_POINTS), afterToday)
        assertEquals(0, s.reader.readCount)
    }

    @Test
    fun moreLegsThanTheCapMakesTheSeriesUnavailableInsteadOfPartial() = runTest {
        val s = setup(
            listOf(cash(1, "1000")),
            List(4) { expense(on(7, 12), "1") },
            maxLegs = 3,
        )

        assertEquals(NetWorthSeries.TooManyLegs, s.run(DateRange(on(7, 10), today)))
    }

    @Test
    fun theReaderIsAskedForAtMostTheChartCapInOneRead() = runTest {
        val s = setup(listOf(cash(1, "1000")), listOf(expense(on(7, 12), "1")))

        s.run(DateRange(on(7, 10), today))

        assertEquals(1, s.reader.readCount)
        assertEquals(MAX_CHART_LEGS, s.reader.lastLimit)
        assertEquals(50_000, MAX_CHART_LEGS)
    }

    @Test
    fun aDebtOwedLaterIsNotInThePastPoints() = runTest {
        val s = setup(
            listOf(cash(1, "1000")),
            listOf(Transaction.OwedBefore(debtId = 1, side = DebtSide.I_OWE, amount = usd("400"), date = on(7, 12))),
        )

        val result = s.run(DateRange(on(7, 10), on(7, 13)))

        assertEquals(
            points(on(7, 10) to "1000", on(7, 11) to "1000", on(7, 12) to "600", on(7, 13) to "600"),
            result,
        )
    }

    @Test
    fun aCardIsOwedOnlyOnceItsBalanceIsBelowZero() = runTest {
        val s = setup(listOf(cash(1, "1000"), card(2, "0")), listOf(expense(on(7, 12), "200", account = 2)))

        val result = s.run(DateRange(on(7, 11), on(7, 13)))

        assertEquals(points(on(7, 11) to "1000", on(7, 12) to "800", on(7, 13) to "800"), result)
    }

    @Test
    fun anArchivedAccountIsLeftOutOfEveryPoint() = runTest {
        val s = setup(listOf(cash(1, "1000"), cash(3, "500", archived = true)), listOf(expense(on(7, 12), "10")))

        val result = s.run(DateRange(on(7, 11), on(7, 12)))

        assertEquals(points(on(7, 11) to "1000", on(7, 12) to "990"), result)
    }

    @Test
    fun otherCurrenciesAreConvertedAtTodaysRate() = runTest {
        val s = setup(
            listOf(cash(1, "100", currency = "EUR")),
            listOf(expense(on(7, 12), "10", currency = "EUR")),
            rates = listOf(ExchangeRate("EUR", BigDecimal("1.10"))),
        )

        val result = s.run(DateRange(on(7, 11), on(7, 12)))

        assertEquals(points(on(7, 11) to "110.00", on(7, 12) to "99.00"), result)
    }

    @Test
    fun eachAmountIsRoundedAtItsOwnConversionAsHomeDoesSoPointsMatchHome() = runTest {
        val s = setup(
            listOf(cash(1, "10.01", currency = "EUR"), cash(2, "10.01", currency = "EUR")),
            rates = listOf(ExchangeRate("EUR", BigDecimal("1.105"))),
        )

        val result = s.run(DateRange(on(7, 14), on(7, 15))) as NetWorthSeries.Trend
        val home = (s.home(s.balances).first() as HomeSummary.Amounts).balance

        assertEquals(usd("22.12"), result.points.last().balance)
        assertEquals(home, result.points.last().balance)
    }

    @Test
    fun aMissingRateHidesTheValuesAndNamesTheCurrency() = runTest {
        val s = setup(listOf(cash(1, "100", currency = "EUR")))

        assertEquals(NetWorthSeries.MissingRate("EUR"), s.run(DateRange(on(7, 11), on(7, 12))))
    }
}
