package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.Money

/**
 * The rule that no debt is ever left owing less than nothing.
 *
 * It is pure: it reads no storage. The save path asks [touchedDebtIds] which debts an entry's old and
 * new legs reach, re-sums each of those inside the runner transaction once the legs are written, and
 * calls [check] on each. A throw there rolls the whole save back, so a refused payment leaves nothing
 * behind. Reading a debt's remaining is added to the ledger port by the first save that needs it.
 */
object DebtGuard {

    /**
     * Ids of the debts [oldLegs] or [newLegs] land on, each once. Account and category legs are
     * ignored.
     *
     * An edit passes the stored entry's legs and the new ones, so a debt the entry moves away from is
     * re-checked too: taking a loan off one person can leave a later repayment to them owing less
     * than nothing. A delete passes no new legs.
     */
    fun touchedDebtIds(oldLegs: Iterable<Leg>, newLegs: Iterable<Leg>): Set<Long> =
        (oldLegs.asSequence() + newLegs.asSequence())
            .mapNotNull { (it.target as? LegTarget.Debt)?.id }
            .toSet()

    /**
     * Refuses a [remaining] below zero on [debt].
     *
     * @throws IllegalArgumentException when [remaining] is not in the debt's currency.
     * @throws WalletException.DebtBelowZero naming the counterparty as typed and how far below zero.
     */
    fun check(debt: Debt, remaining: Money) {
        require(remaining.currencyCode == debt.currencyCode) {
            "remaining currency ${remaining.currencyCode} != debt currency ${debt.currencyCode}"
        }
        if (remaining.isNegative) throw WalletException.DebtBelowZero(debt.counterparty, -remaining)
    }
}
