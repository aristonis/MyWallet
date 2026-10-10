package org.aristonis.mywallet.domain.error

import org.aristonis.mywallet.domain.model.Money

/**
 * Typed, fail-loud domain errors. Sealed so callers/tests can exhaustively react to each
 * kind, and so we never throw a vague `Exception("...")`. Each subclass keeps the offending value.
 */
sealed class WalletException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class AccountNotFound(val id: Long) :
        WalletException("Account $id not found")

    /** The account has transactions, so it can't be hard-deleted — archive it instead. */
    class AccountInUse(val id: Long) :
        WalletException("Account $id has transactions and cannot be deleted; archive it instead")

    /** The account has transactions, so its currency is fixed — changing it would re-denominate that recorded money. */
    class AccountCurrencyLocked(val id: Long) :
        WalletException("Account $id has transactions; its currency can't be changed")

    /** The account is archived, so no new transaction may be recorded against it (unarchive first). */
    class AccountArchived(val id: Long) :
        WalletException("Account $id is archived; unarchive it to record transactions")

    class CategoryNotFound(val id: Long) :
        WalletException("Category $id not found")

    /** A sibling category (same kind, same parent) already uses this name, ignoring case. */
    class DuplicateCategoryName(val name: String, cause: Throwable? = null) :
        WalletException("A category named \"$name\" already exists here", cause)

    /** Categories stop at two levels, so a sub-category can never itself become a parent. */
    class CategoryDepthExceeded(val parentId: Long) :
        WalletException("Category $parentId is already a sub-category; categories go two levels deep")

    /**
     * A sub-category and the parent it was paired with don't belong together: either the two are of
     * different income/expense kinds, or the sub-category hangs under some other category entirely.
     */
    class CategoryKindMismatch(val parentId: Long) :
        WalletException("Category $parentId and the sub-category paired with it don't match: wrong kind, or not its child")

    /**
     * A leg's category is of the other kind than the leg counts as: an income category on an
     * expense, say. Saved anyway, the money would count on the wrong side of Tracking.
     */
    class CategoryWrongKind(val categoryId: Long) :
        WalletException("Category $categoryId is of the wrong kind for this entry")

    /**
     * The stored category tree is not shaped the way every reader assumes — a row parented under
     * itself, or an app-owned bucket filed under a category being deleted. Only reachable from data
     * that arrived from outside, so it names the row rather than pretending the operation succeeded.
     */
    class CategoryStructureInvalid(val id: Long) :
        WalletException("Category $id is part of a broken category tree and cannot be used")

    /**
     * A transaction's main category must be a top-level one. A sub-category used there would be
     * invisible to the delete path — that only clears the finer `subCategoryId` column — so the row
     * would end up naming a category that no longer exists, with nothing to flag it.
     */
    class CategoryNotTopLevel(val id: Long) :
        WalletException("Category $id is a sub-category; a transaction's main category must be a top-level one")

    /** The two Uncategorized buckets belong to the app; renaming or deleting one is refused. */
    class SystemCategoryProtected(val id: Long) :
        WalletException("Category $id is managed by the app and can't be renamed or deleted")

    /** The transaction amount's currency does not match the account it lands on. */
    class CurrencyMismatch(val amountCurrency: String, val accountCurrency: String) :
        WalletException("currency mismatch: amount is $amountCurrency but account is $accountCurrency")

    /** A currency referenced by an account is not in the currency list (data-integrity guard). */
    class CurrencyNotFound(val code: String) :
        WalletException("Currency $code not found")

    /**
     * Re-expressing a rate against a new base left nothing inside the scale the app stores. The two
     * currencies are too far apart in magnitude for one to be written against the other.
     */
    class RateUnderflow(val code: String) :
        WalletException("The rate for $code is too small to express against the new base currency")

    /** A conversion needs a rate for a non-base currency and none is set. Refused rather than guessed. */
    class MissingRate(val code: String) :
        WalletException("No exchange rate set for $code")

    /** The transaction being edited no longer exists (e.g. it was deleted since the editor opened). */
    class TransactionNotFound(val id: Long) :
        WalletException("Transaction $id not found")

    /**
     * An entry was put back at an id another entry already holds, e.g. the same undo ran twice.
     * Nothing was written: an undo never overwrites.
     */
    class EntryIdTaken(val id: Long) :
        WalletException("Entry id $id is already taken")

    /** A transfer edit tried to change its currency pair; its stored rate would no longer apply. */
    class TransferCurrencyPairChanged(val id: Long) :
        WalletException("Transaction $id is a transfer whose currencies can't change on edit — delete and re-add instead")

    /** A cross-currency conversion rounded the amount down to zero in the target currency — enter more. */
    class AmountRoundsToZero(val currencyCode: String) :
        WalletException("The amount is too small to convert to $currencyCode; it rounds to zero")

    /** A name that is empty once trimmed. A debt is found by its person's name, so it can't be blank. */
    class NameBlank :
        WalletException("A name is required")

    /**
     * A payment or edit would leave the debt with [counterparty] below zero. Paying more than was owed
     * would make the debt negative, which means the other side now owes, so the change is refused and
     * nothing is saved. [amount] is how far below zero it would have gone, as a positive value.
     */
    class DebtBelowZero(val counterparty: String, val amount: Money) :
        WalletException("That would take the debt with $counterparty $amount below zero")

    /**
     * Another debt on the same side and in the same currency already answers to [counterparty], even
     * one no entry points at any more. Two debts under one name would be one person's money split in
     * two, and merging them would mix two people's money, so the rename is refused.
     */
    class DebtNameTaken(val counterparty: String) :
        WalletException("Another debt on this side and currency is already named $counterparty")

    /**
     * A stored row (an entry, or a debt reported under [DEBT_KIND]) that cannot be read back. It is the
     * only error the read side throws for bad stored data, so screens can name the row instead of crashing.
     */
    class UnreadableRow(val id: Long, val kind: String, cause: Throwable? = null) :
        WalletException("Row $id of kind $kind could not be read", cause) {
        companion object {
            /** The kind a debt row is reported under, since a debt has no entry kind of its own. */
            const val DEBT_KIND = "DEBT"
        }
    }

    /** A backup file could not be read — malformed, truncated, or missing required fields. */
    class BackupInvalid(cause: Throwable? = null) :
        WalletException("Backup data could not be read: it is corrupt or in an unrecognized format", cause)

    /** A backup was written in a format version this build doesn't know how to restore. */
    class BackupVersionUnsupported(val version: Int) :
        WalletException("Backup format version $version is not supported")
}
