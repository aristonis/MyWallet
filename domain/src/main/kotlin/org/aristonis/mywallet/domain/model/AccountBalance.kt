package org.aristonis.mywallet.domain.model

/**
 * An account paired with its current computed balance (in the account's own currency). [credit] is
 * set only for a type whose negative balance is owed (a credit card), and says what is owed and how
 * much credit is left; it is null for every asset account.
 */
data class AccountBalance(
    val account: Account,
    val balance: Money,
    val credit: CreditStatus? = null,
)
