package org.aristonis.mywallet.domain.model

/** An account paired with its current computed balance (in the account's own currency). */
data class AccountBalance(
    val account: Account,
    val balance: Money,
)
