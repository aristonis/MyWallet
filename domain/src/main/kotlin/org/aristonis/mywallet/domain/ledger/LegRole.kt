package org.aristonis.mywallet.domain.ledger

/**
 * What a leg means inside its entry. The names are stored as text, so once a release ships them they
 * never change: renaming one would leave every stored leg of that role unreadable.
 *
 * Tracking places a category leg from its entry's kind and role alone, so a role counts one way only.
 * That is why repayment interest is two roles, as a write-off is LOSS or FORGIVEN.
 */
enum class LegRole {
    ACCOUNT,
    SOURCE,
    DEST,
    DEBT,
    MAIN,
    FEE,
    DEDUCTION,
    DEDUCTION_DEBT,

    /** Interest on a repayment that pays an I-owe debt; counts as expense. */
    INTEREST_PAID,

    /** Interest on a repayment that collects an owed-to-me debt; counts as income. */
    INTEREST_RECEIVED,

    LOSS,
    FORGIVEN,

    /** The part of an expense owed rather than paid now; counts as expense. */
    PAID_LATER,
}
