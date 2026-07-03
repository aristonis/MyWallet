package org.aristonis.mywallet.domain.error

/**
 * Typed, fail-loud domain errors (NFR-9). Sealed so callers/tests can exhaustively react to each
 * kind, and so we never throw a vague `Exception("...")`. Each subclass keeps the offending value.
 */
sealed class WalletException(message: String) : Exception(message) {

    class AccountNotFound(val id: Long) :
        WalletException("Account $id not found")

    /** The account has transactions, so it can't be hard-deleted — archive it instead. */
    class AccountInUse(val id: Long) :
        WalletException("Account $id has transactions and cannot be deleted; archive it instead")

    /** The account is archived, so no new transaction may be recorded against it (unarchive first). */
    class AccountArchived(val id: Long) :
        WalletException("Account $id is archived; unarchive it to record transactions")

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

    /** The transaction being edited no longer exists (e.g. it was deleted since the editor opened). */
    class TransactionNotFound(val id: Long) :
        WalletException("Transaction $id not found")

    /** A transfer edit tried to change its currency pair; its stored rate would no longer apply. */
    class TransferCurrencyPairChanged(val id: Long) :
        WalletException("Transaction $id is a transfer whose currencies can't change on edit — delete and re-add instead")
}
