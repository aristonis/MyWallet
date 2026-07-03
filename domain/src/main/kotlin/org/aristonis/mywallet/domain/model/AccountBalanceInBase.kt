package org.aristonis.mywallet.domain.model

/**
 * An account's balance shown two ways: [native] in the account's own currency, and [base] converted
 * to the app base currency. [base] is `null` when that currency has no exchange rate — the card
 * shows a "needs a rate" hint instead of a wrong number (fail-loud, per account, so the rest of the
 * list still renders).
 */
data class AccountBalanceInBase(
    val account: Account,
    val native: Money,
    val base: Money?,
)
