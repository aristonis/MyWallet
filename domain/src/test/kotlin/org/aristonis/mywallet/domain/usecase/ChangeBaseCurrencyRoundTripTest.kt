package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.usecase.fake.DirectTransactionRunner
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.FakeCurrencyRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeLedgerQueries
import org.aristonis.mywallet.domain.usecase.fake.FakeRateRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeSettingsRepository
import org.aristonis.mywallet.domain.usecase.fake.LegApplyingBaseCurrencyRepository
import org.aristonis.mywallet.domain.usecase.fake.fakeFx
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/**
 * Switching the base currency away and back must leave every Tracking total where it was, even when
 * the divisor has no finite decimal inverse (163 here) and the leg lands exactly half-way between two
 * minor units.
 *
 * Assumed behaviour: a leg's amount times its rate is rounded to scale 20 with HALF_EVEN before it is
 * rounded to the base currency's decimals, so the tiny residue a round trip leaves in the stored rate
 * cannot tip a half-way value to the wrong side. Uses the assumed constructor
 * `ChangeBaseCurrency(..., ledger, runner: TransactionRunner)`.
 */
class ChangeBaseCurrencyRoundTripTest {

    private val currencies = listOf(
        Currency("USD", "$", 2),
        Currency("EUR", "€", 2),
        Currency("GBP", "£", 2),
        Currency("JPY", "¥", 0),
    )
    private val divisor = BigDecimal("163")

    /** Switches [from] to [via] at [divisor] and straight back, over the legs held by [entries]. */
    private suspend fun roundTrip(entries: FakeEntryRepository, from: String, via: String, rates: List<ExchangeRate>) {
        val away = LegApplyingBaseCurrencyRepository(entries)
        changeBase(entries, away, from, rates)(via, divisor)
        val back = LegApplyingBaseCurrencyRepository(entries)
        changeBase(entries, back, via, away.lastRates!!)(from)
    }

    private fun changeBase(
        entries: FakeEntryRepository,
        target: LegApplyingBaseCurrencyRepository,
        base: String,
        rates: List<ExchangeRate>,
    ) = ChangeBaseCurrency(
        currencies = FakeCurrencyRepository(currencies),
        rates = FakeRateRepository(rates),
        settings = FakeSettingsRepository(Settings(baseCurrencyCode = base)),
        baseCurrency = target,
        ledger = FakeLedgerQueries(entries),
        runner = DirectTransactionRunner(),
    )

    private suspend fun trackedExpense(entries: FakeEntryRepository, base: String): Money {
        val result = ComputeCategoryBreakdown(
            ledger = FakeLedgerQueries(entries),
            fx = fakeFx(currencies, emptyList(), base),
        )(DateRange.ALL_TIME).first() as CategoryBreakdownResult.Resolved
        return result.breakdown.expense.single().total
    }

    @Test
    fun aHalfWayLegInAZeroDecimalBaseKeepsItsTotalAfterABaseRoundTrip() = runTest {
        // 0.01 USD x 150 = 1.5 JPY, which counts as 2 JPY.
        val entries = FakeEntryRepository(
            listOf(EntryWorld.expense(id = 1, amount = "0.01", currency = "USD", rate = "150")),
        )
        val before = trackedExpense(entries, "JPY")

        roundTrip(entries, from = "JPY", via = "EUR", rates = listOf(ExchangeRate("USD", BigDecimal("150"))))

        assertEquals(Money.of("2", "JPY"), before)
        assertEquals(before, trackedExpense(entries, "JPY"))
    }

    @Test
    fun aHalfWayLegInATwoDecimalBaseKeepsItsTotalAfterABaseRoundTrip() = runTest {
        // 0.10 EUR x 1.35 = 0.135 USD, which counts as 0.14 USD.
        val entries = FakeEntryRepository(
            listOf(EntryWorld.expense(id = 1, amount = "0.10", currency = "EUR", rate = "1.35")),
        )
        val before = trackedExpense(entries, "USD")

        roundTrip(entries, from = "USD", via = "GBP", rates = listOf(ExchangeRate("EUR", BigDecimal("1.35"))))

        assertEquals(Money.of("0.14", "USD"), before)
        assertEquals(before, trackedExpense(entries, "USD"))
    }
}
