package org.aristonis.mywallet.domain.ledger

import org.aristonis.mywallet.domain.model.Money
import java.math.BigDecimal

/**
 * One signed amount of an entry on one target. Only category legs carry [rateToBase], because only
 * they are converted for Tracking; balances stay in each account's or debt's own currency.
 *
 * No checks run at construction: legs are built from stored rows, and a bad row must surface as
 * an unreadable entry from the recipe, not as an exception thrown while mapping.
 *
 * @property position Orders several legs of the same role; 0 when the role occurs once. A DEDUCTION
 *   leg sits at its line's position (every line has one, so these are 0..n-1); a DEDUCTION_DEBT leg
 *   sits at the position of the owed-later line it belongs to, so these may skip positions.
 * @property rateToBase The rate to the base currency the leg was recorded with; null when none is stored.
 */
data class Leg(
    val role: LegRole,
    val position: Int,
    val target: LegTarget,
    val amount: Money,
    val rateToBase: BigDecimal? = null,
)
