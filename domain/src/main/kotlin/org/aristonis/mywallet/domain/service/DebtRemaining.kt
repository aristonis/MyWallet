package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.port.DebtLeg

/** What is left on one debt: its [side] and the [remaining] amount in the debt's currency. */
data class DebtBalance(val side: DebtSide, val remaining: Money)

/** The one place a debt's remaining is worked out, so every screen and guard reads the same number. */
object DebtRemaining {

    /** One entry per debt id: the sum of its legs, a negative sum kept as it is. */
    fun byDebt(legs: List<DebtLeg>): Map<Long, DebtBalance> =
        legs.groupBy { it.debtId }.mapValues { (_, group) ->
            DebtBalance(group.first().side, group.map { it.amount }.reduce(Money::plus))
        }
}
