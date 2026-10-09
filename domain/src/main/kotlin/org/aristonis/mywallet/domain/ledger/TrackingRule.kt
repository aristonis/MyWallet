package org.aristonis.mywallet.domain.ledger

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
}
