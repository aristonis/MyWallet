package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.port.TransactionRunner
import java.math.BigDecimal

/**
 * Records a transaction and keeps the rates typed for it as the saved rates, in one database
 * transaction. The record runs first: if it is refused (an amount that rounds to nothing, a category
 * deleted meanwhile), the rates are never written, so a mistyped rate cannot reprice every total
 * behind an error that says nothing changed. Inside the transaction a failure rolls both back.
 */
class RecordWithTypedRates(
    private val runner: TransactionRunner,
    private val setExchangeRate: SetExchangeRate,
) {
    suspend operator fun <T> invoke(ratesToKeep: Map<String, BigDecimal>, record: suspend () -> T): T =
        runner.inTransaction {
            val result = record()
            ratesToKeep.forEach { (code, rate) -> setExchangeRate(code, rate) }
            result
        }
}
