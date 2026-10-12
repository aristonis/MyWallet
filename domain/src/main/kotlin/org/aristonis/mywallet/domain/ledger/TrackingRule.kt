package org.aristonis.mywallet.domain.ledger

import org.aristonis.mywallet.domain.model.CategoryKind

/**
 * How a category leg counts in Tracking. Kept per leg role rather than per kind, because one entry
 * can mix them (an income with deduction expenses, a transfer with a fee).
 *
 * A rule never changes the sign of a leg; the sign is stored on the leg.
 */
enum class TrackingRule {
    /** Adds to income. */
    INCOME,

    /** Adds to expense. */
    EXPENSE,

    /** Takes away from expense, as a refund does. */
    REDUCES_EXPENSE,

    /** Never counts; such a role never targets a category. */
    NOT_COUNTED,
    ;

    /**
     * The kind of category a leg under this rule is filed in: income under an income category, an
     * expense and a refund of one under an expense category.
     *
     * @throws IllegalStateException for [NOT_COUNTED], which never sits on a category.
     */
    fun categoryKind(): CategoryKind = when (this) {
        INCOME -> CategoryKind.INCOME
        EXPENSE, REDUCES_EXPENSE -> CategoryKind.EXPENSE
        NOT_COUNTED -> error("a leg that never counts cannot be on a category")
    }
}
