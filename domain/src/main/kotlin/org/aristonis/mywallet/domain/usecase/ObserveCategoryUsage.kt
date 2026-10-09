package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.Flow
import org.aristonis.mywallet.domain.port.LedgerQueries

/**
 * Live count of entries per category id, an entry counting once under its main category and once
 * under its sub-category. A category no entry uses is absent from the map. The category screens use
 * it to show how much a rename or delete would touch.
 */
class ObserveCategoryUsage(private val ledger: LedgerQueries) {

    operator fun invoke(): Flow<Map<Long, Int>> = ledger.observeCategoryUsage()
}
