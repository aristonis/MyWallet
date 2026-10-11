package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.Money

/**
 * Prevents debt writes from creating or worsening a negative remaining balance.
 * Existing invalid balances may stay unchanged or improve so their history can be repaired.
 * Writers read the touched debts before and after the write inside the same transaction;
 * a refusal rolls back the whole write.
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
     * Refuses a [remaining] below zero unless an already negative balance stays the same or improves.
     *
     * @throws IllegalArgumentException when [remaining] is not in the debt's currency.
     * @throws WalletException.DebtBelowZero naming the counterparty as typed and how far below zero.
     */
    fun check(debt: Debt, remaining: Money, previousRemaining: Money? = null) {
        require(remaining.currencyCode == debt.currencyCode) {
            "remaining currency ${remaining.currencyCode} != debt currency ${debt.currencyCode}"
        }
        if (previousRemaining != null) require(previousRemaining.currencyCode == debt.currencyCode)
        val improvesExistingNegative = previousRemaining != null && previousRemaining.isNegative && remaining >= previousRemaining
        if (remaining.isNegative && !improvesExistingNegative) {
            throw WalletException.DebtBelowZero(debt.counterparty, -remaining)
        }
    }
}
