package org.aristonis.mywallet.domain.usecase.fake

import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.port.BaseCurrencyRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.LegRate
import org.aristonis.mywallet.domain.port.LegRateChange
import org.aristonis.mywallet.domain.port.TransactionRunner

/**
 * A runner that writes "begin" and "end" into [log] around every block and serialises competing
 * writers the way a database transaction does: a write offered through [offerWrite] while a block is
 * open waits for the block to end, so nothing can land between two reads and writes of one block.
 */
class OrderRecordingRunner(private val log: MutableList<String>) : TransactionRunner {

    var blocks = 0
        private set
    private var open = false
    private val waiting = mutableListOf<String>()

    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        blocks++
        open = true
        log += "begin"
        try {
            return block()
        } finally {
            open = false
            log += "end"
            waiting.forEach { log += it }
            waiting.clear()
        }
    }

    /** A write by someone else: logged now when no block is open, otherwise right after it ends. */
    fun offerWrite(name: String) {
        if (open) waiting += name else log += name
    }
}

/**
 * Logs `distinctLegRates` into [log] and then offers [competingWrite] to [runner], standing for a
 * leg another writer saves just after the plan was read. Everything else passes to [delegate].
 */
class PlanReadLoggingLedger(
    private val delegate: LedgerQueries,
    private val log: MutableList<String>,
    private val runner: OrderRecordingRunner,
    private val competingWrite: String,
) : LedgerQueries by delegate {

    override suspend fun distinctLegRates(): List<LegRate> {
        log += "distinctLegRates"
        return delegate.distinctLegRates().also { runner.offerWrite(competingWrite) }
    }
}

/** Logs "rebase" into [log] and passes the call on to [delegate]. */
class RebaseLoggingRepository(
    private val delegate: BaseCurrencyRepository,
    private val log: MutableList<String>,
) : BaseCurrencyRepository {

    override suspend fun rebase(
        newBaseCurrencyCode: String,
        rates: List<ExchangeRate>,
        legRates: List<LegRateChange>,
    ) {
        log += "rebase"
        delegate.rebase(newBaseCurrencyCode, rates, legRates)
    }
}
