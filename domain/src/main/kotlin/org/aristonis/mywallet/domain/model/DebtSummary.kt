package org.aristonis.mywallet.domain.model

import java.time.LocalDate

/**
 * What a debt shows on screen. [HIDDEN] is a debt no entry points at any more (every leg on it was
 * deleted), so it is kept for its name but not listed.
 */
enum class DebtState { OPEN, OVERDUE, SETTLED, HIDDEN }

/**
 * A debt with what still remains on it and the state that follows from the two.
 *
 * @property remaining In the debt's own currency. A negative value is not judged here: the save guard
 *   and restore keep it at zero or more, and readers report one that slips through via ledger health.
 *   If one does reach here, it reads as [DebtState.OPEN].
 */
data class DebtSummary(
    val debt: Debt,
    val remaining: Money,
    val state: DebtState,
) {
    companion object {
        /**
         * Builds the summary. [today] is passed in rather than read, so the same rows always give the
         * same state and a test can pin the day.
         *
         * Overdue needs both a past due date and money still owed: a debt paid off late is settled,
         * not overdue, and one due today still has the rest of the day.
         *
         * @param hasLegs Whether any entry still points at the debt.
         * @throws IllegalArgumentException when [remaining] is not in the debt's currency.
         */
        fun of(debt: Debt, remaining: Money, hasLegs: Boolean, today: LocalDate): DebtSummary {
            require(remaining.currencyCode == debt.currencyCode) {
                "remaining currency ${remaining.currencyCode} != debt currency ${debt.currencyCode}"
            }
            return DebtSummary(debt, remaining, stateOf(debt.dueDate, remaining, hasLegs, today))
        }

        private fun stateOf(dueDate: LocalDate?, remaining: Money, hasLegs: Boolean, today: LocalDate): DebtState =
            when {
                !hasLegs -> DebtState.HIDDEN
                remaining.isZero -> DebtState.SETTLED
                dueDate != null && dueDate.isBefore(today) && remaining.isPositive -> DebtState.OVERDUE
                else -> DebtState.OPEN
            }
    }
}
