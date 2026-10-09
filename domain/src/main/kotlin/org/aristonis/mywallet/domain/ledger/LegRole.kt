package org.aristonis.mywallet.domain.ledger

/**
 * What a leg means inside its entry. The names are stored as text, so once a release ships them they
 * never change: renaming one would leave every stored leg of that role unreadable.
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
    INTEREST,
    LOSS,
    FORGIVEN,
}
