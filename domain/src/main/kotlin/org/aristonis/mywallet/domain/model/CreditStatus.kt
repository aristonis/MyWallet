package org.aristonis.mywallet.domain.model

/**
 * Where a credit account stands: [owed] is what the owner owes the bank, [available] is the credit
 * left to spend (null when no limit is set), and [overLimit] flags spending past the limit. Going
 * over is allowed, because the app records what happened rather than refusing it, so it is only
 * flagged here.
 */
data class CreditStatus(
    val owed: Money,
    val available: Money?,
    val overLimit: Boolean,
) {
    companion object {
        /**
         * Reads [balance] as a credit account's balance. A negative balance is debt, so owed is its
         * negation; a positive one (paid more than was owed) owes nothing. Available credit is the
         * limit less what is owed, never below zero. Everything stays in the balance's currency.
         */
        fun of(balance: Money, limit: Money?): CreditStatus {
            val zero = Money.zero(balance.currencyCode)
            val owed = maxOf(-balance, zero)
            return CreditStatus(
                owed = owed,
                available = limit?.let { maxOf(it - owed, zero) },
                overLimit = limit != null && owed > limit,
            )
        }
    }
}
