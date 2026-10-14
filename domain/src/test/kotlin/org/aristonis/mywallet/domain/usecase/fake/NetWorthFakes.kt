package org.aristonis.mywallet.domain.usecase.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.port.DatedAccountLeg
import org.aristonis.mywallet.domain.port.DatedDebtLeg
import org.aristonis.mywallet.domain.port.DatedLegs
import org.aristonis.mywallet.domain.port.NetWorthLegReader
import java.time.LocalDate

/**
 * Reads the dated legs from the same entries the balances come from, so a test cannot give the two
 * different ledgers. [readCount] says how many times the series asked, for the "reads nothing"
 * cases.
 */
class FakeNetWorthLegReader(private val entries: FakeEntryRepository) : NetWorthLegReader {

    var readCount: Int = 0
        private set

    /** The limit of the latest read. */
    var lastLimit: Int = 0
        private set

    override fun observeLegsAfter(after: LocalDate, limit: Int): Flow<DatedLegs> {
        readCount++
        lastLimit = limit
        return entries.rows.map { rows ->
            val later = rows.filter { it.header.date.isAfter(after) }
            val account = later.flatMap { row ->
                row.legs.mapNotNull { leg ->
                    (leg.target as? LegTarget.Account)?.let { DatedAccountLeg(row.header.date, it.id, leg.amount) }
                }
            }
            val debt = later.flatMap { row ->
                row.legs.mapNotNull { leg ->
                    (leg.target as? LegTarget.Debt)?.let { DatedDebtLeg(row.header.date, it.id, leg.amount) }
                }
            }
            if (account.size + debt.size > limit) DatedLegs(emptyList(), emptyList(), overLimit = true)
            else DatedLegs(account, debt, overLimit = false)
        }
    }
}
