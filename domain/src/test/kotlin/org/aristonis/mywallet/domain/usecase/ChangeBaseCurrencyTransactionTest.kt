package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.FakeBaseCurrencyRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeCurrencyRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeLedgerQueries
import org.aristonis.mywallet.domain.usecase.fake.FakeRateRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeSettingsRepository
import org.aristonis.mywallet.domain.usecase.fake.OrderRecordingRunner
import org.aristonis.mywallet.domain.usecase.fake.PlanReadLoggingLedger
import org.aristonis.mywallet.domain.usecase.fake.RebaseLoggingRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/**
 * A base change plans its leg changes and applies them in one transaction, so no leg can be saved
 * between the read and the write and be left out of the plan.
 *
 * Assumed API: `ChangeBaseCurrency` takes a last constructor parameter
 * `runner: org.aristonis.mywallet.domain.port.TransactionRunner`, and calls `ledger.distinctLegRates()`
 * and `baseCurrency.rebase(...)` inside one `runner.inTransaction { }` block.
 */
class ChangeBaseCurrencyTransactionTest {

    private val log = mutableListOf<String>()
    private val runner = OrderRecordingRunner(log)

    private fun useCase(): ChangeBaseCurrency {
        val entries = FakeEntryRepository(
            listOf(EntryWorld.expense(id = 1, currency = "JPY", amount = "4000", rate = "0.0025")),
        )
        return ChangeBaseCurrency(
            currencies = FakeCurrencyRepository(
                listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2), Currency("JPY", "¥", 0)),
            ),
            rates = FakeRateRepository(listOf(ExchangeRate("EUR", BigDecimal("1.25")), ExchangeRate("JPY", BigDecimal("0.0025")))),
            settings = FakeSettingsRepository(Settings(baseCurrencyCode = "USD")),
            baseCurrency = RebaseLoggingRepository(FakeBaseCurrencyRepository(), log),
            ledger = PlanReadLoggingLedger(FakeLedgerQueries(entries), log, runner, competingWrite = "competingWrite"),
            runner = runner,
        )
    }

    @Test
    fun theLegRatePlanIsReadAndAppliedInsideOneTransaction() = runTest {
        useCase()("EUR")

        assertEquals(1, runner.blocks)
        assertEquals(listOf("begin", "distinctLegRates", "rebase", "end"), log.take(4))
    }

    @Test
    fun aLegSavedByAnotherWriterWaitsUntilTheRebaseHasFinished() = runTest {
        useCase()("EUR")

        // The competing write is offered right after the plan was read. Inside the same block it
        // cannot land before the rebase, so it is never left out of a plan it is not part of.
        assertEquals(listOf("begin", "distinctLegRates", "rebase", "end", "competingWrite"), log)
    }

    @Test
    fun switchingToTheCurrentBaseOpensNoTransactionAndReadsNoPlan() = runTest {
        useCase()("USD")

        assertEquals(emptyList<String>(), log)
    }
}
