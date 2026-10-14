package org.aristonis.mywallet.domain.port

import kotlinx.coroutines.flow.Flow
import org.aristonis.mywallet.domain.model.Money
import java.time.LocalDate

/**
 * Reads the account and debt legs a net worth series subtracts from today's balances. Kept apart
 * from [LedgerQueries] so the balance and Tracking reads, and every fake of them, stay as they are.
 */
interface NetWorthLegReader {

    /**
     * Account and debt legs of entries dated strictly after [after], with no upper bound: a balance
     * now includes future-dated entries, so a past point has to take those away too. One bounded
     * read; when the two kinds together hold more than [limit] legs, [DatedLegs.overLimit] is true
     * and the lists hold only part of them. An amount that does not parse fails the flow with
     * [org.aristonis.mywallet.domain.error.WalletException.UnreadableRow].
     */
    fun observeLegsAfter(after: LocalDate, limit: Int): Flow<DatedLegs>
}

/** The legs [NetWorthLegReader] found, each with the day of its entry. */
data class DatedLegs(
    val account: List<DatedAccountLeg>,
    val debt: List<DatedDebtLeg>,
    val overLimit: Boolean,
)

/** One account leg: its signed amount in the account's currency, and the day of its entry. */
data class DatedAccountLeg(val date: LocalDate, val accountId: Long, val amount: Money)

/** One debt leg: its signed amount in the debt's currency, and the day of its entry. */
data class DatedDebtLeg(val date: LocalDate, val debtId: Long, val amount: Money)
