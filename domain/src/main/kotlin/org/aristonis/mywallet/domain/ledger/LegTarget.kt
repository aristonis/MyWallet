package org.aristonis.mywallet.domain.ledger

import org.aristonis.mywallet.domain.model.DebtSide

/**
 * The one place a leg's money lands. Sealed so a leg can never point at two things at once, and so
 * balances (accounts, debts) and Tracking (categories) each pick out only their own legs.
 */
sealed interface LegTarget {

    /** Money in (+) or out (-) of an account. */
    data class Account(val id: Long) : LegTarget

    /**
     * A debt's remaining growing (+) or shrinking (-).
     *
     * @property side The debt row's own side. The storage mapper fills it from the debts table when
     *   reading, so a leg can never disagree with its debt.
     */
    data class Debt(val id: Long, val side: DebtSide) : LegTarget

    /** Counts on the category's own side (+), or against it (-) as a refund does. */
    data class Category(val id: Long, val subId: Long?) : LegTarget
}
