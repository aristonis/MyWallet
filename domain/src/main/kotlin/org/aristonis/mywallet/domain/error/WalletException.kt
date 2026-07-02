package org.aristonis.mywallet.domain.error

/**
 * Typed, fail-loud domain errors (NFR-9). Sealed so callers/tests can exhaustively react to each
 * kind, and so we never throw a vague `Exception("...")`. Each subclass keeps the offending value.
 */
sealed class WalletException(message: String) : Exception(message) {

    class AccountNotFound(val id: Long) :
        WalletException("Account $id not found")

    class CategoryNotFound(val id: Long) :
        WalletException("Category $id not found")

    /** The transaction amount's currency does not match the account it lands on. */
    class CurrencyMismatch(val amountCurrency: String, val accountCurrency: String) :
        WalletException("currency mismatch: amount is $amountCurrency but account is $accountCurrency")

    /** A currency referenced by an account is not in the currency list (data-integrity guard). */
    class CurrencyNotFound(val code: String) :
        WalletException("Currency $code not found")

    /** No exchange rate is set for a non-base currency that a conversion needs (FR-15 fail-loud). */
    class MissingRate(val code: String) :
        WalletException("No exchange rate set for $code")
}
