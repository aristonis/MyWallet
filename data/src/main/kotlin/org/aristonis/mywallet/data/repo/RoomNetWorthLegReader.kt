package org.aristonis.mywallet.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.aristonis.mywallet.data.db.DatedAccountLegRow
import org.aristonis.mywallet.data.db.DatedDebtLegRow
import org.aristonis.mywallet.data.db.LedgerQueryDao
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.DatedAccountLeg
import org.aristonis.mywallet.domain.port.DatedDebtLeg
import org.aristonis.mywallet.domain.port.DatedLegs
import org.aristonis.mywallet.domain.port.NetWorthLegReader
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The dated account and debt legs behind the net worth series. Each kind is read with one row more
 * than [limit], so the two together show whether the cap was passed; past it nothing is parsed and
 * no partial list leaves this class. Amounts are parsed here from their exact text; the sums happen
 * later in the domain with BigDecimal.
 */
class RoomNetWorthLegReader(private val dao: LedgerQueryDao) : NetWorthLegReader {

    override fun observeLegsAfter(after: LocalDate, limit: Int): Flow<DatedLegs> =
        combine(
            dao.observeAccountLegsAfter(after, limit + 1),
            dao.observeDebtLegsAfter(after, limit + 1),
        ) { accountRows, debtRows ->
            if (accountRows.size + debtRows.size > limit) {
                DatedLegs(emptyList(), emptyList(), overLimit = true)
            } else {
                DatedLegs(accountRows.map { it.toLeg() }, debtRows.map { it.toLeg() }, overLimit = false)
            }
        }

    private suspend fun DatedAccountLegRow.toLeg(): DatedAccountLeg = try {
        DatedAccountLeg(date, accountId, Money.of(BigDecimal(amount), currencyCode))
    } catch (e: IllegalArgumentException) {
        throw unreadableAccountLeg(dao, accountId, amount, e)
    }

    private suspend fun DatedDebtLegRow.toLeg(): DatedDebtLeg = try {
        DatedDebtLeg(date, debtId, Money.of(BigDecimal(amount), currencyCode))
    } catch (e: IllegalArgumentException) {
        throw unreadableDebtLeg(dao, debtId, amount, e)
    }
}
